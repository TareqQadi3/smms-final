package com.smms.procurement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.Center;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.idempotency.IdempotencyService;
import com.smms.idempotency.IdempotencyService.Outcome;
import com.smms.idempotency.IdempotencyService.Result;
import com.smms.inventory.api.StockLedger;
import com.smms.inventory.api.StockLedger.ItemRef;
import com.smms.inventory.api.StockLedger.MovementKind;
import com.smms.inventory.api.StockLedger.MovementRef;
import com.smms.inventory.api.StockLedger.StockCommand;
import com.smms.materials.entity.MaterialRequest;
import com.smms.materials.repository.MaterialRequestRepository;
import com.smms.procurement.CenterScope;
import com.smms.procurement.DateRange;
import com.smms.procurement.ProcurementProperties;
import com.smms.procurement.Quantities;
import com.smms.procurement.dto.PurchaseOrderDtos.*;
import com.smms.procurement.dto.Refs.SupplierRef;
import com.smms.procurement.entity.*;
import com.smms.procurement.repository.PoReceiptRepository;
import com.smms.procurement.repository.PurchaseOrderRepository;
import com.smms.security.TenantSecurityService;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.util.InputSanitizer;
import com.smms.util.PageRequests;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Purchase orders: draft, submit, approve/reject (segregation of duties), receive (goods receipt with
 * stock-in through the ledger, over-receipt prevention, idempotent), cancel, short-close.
 *
 * <p>Locking order: the PO header row is locked (FOR UPDATE, 5 s timeout) before the ledger locks the
 * stock balances.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    public static final UserRole[] READ = {UserRole.system_admin, UserRole.region_supervisor,
        UserRole.hospital_supervisor, UserRole.maintenance_manager, UserRole.operating_company_manager,
        UserRole.warehouse};
    public static final UserRole[] EDIT = {UserRole.warehouse, UserRole.maintenance_manager};
    public static final UserRole[] APPROVE = {UserRole.operating_company_manager, UserRole.maintenance_manager,
        UserRole.system_admin};
    public static final UserRole[] CANCEL_ANY = {UserRole.maintenance_manager, UserRole.operating_company_manager,
        UserRole.system_admin};
    public static final UserRole[] RECEIVE = {UserRole.warehouse};
    public static final UserRole[] CLOSE = {UserRole.maintenance_manager, UserRole.operating_company_manager};
    public static final Map<String, String> SORT = Map.of("createdAt", "createdAt", "poNumber", "poNumber",
        "status", "status", "expectedDate", "expectedDate");
    private static final Map<String, Object> LOCK_HINT = Map.of("jakarta.persistence.lock.timeout", 5000);

    private final PurchaseOrderRepository repo;
    private final PoReceiptRepository receiptRepo;
    private final MaterialRequestRepository mrRepo;
    private final SupplierService suppliers;
    private final TenantSecurityService tenantSecurity;
    private final WorkOrderAccessPolicy woAccess;
    private final StockLedger ledger;
    private final RefLookup lookup;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;
    private final ProcurementNotifier notifier;
    private final ProcurementProperties props;
    private final EntityManager em;

    public static Pageable pageable(int page, int size, List<String> sort) {
        return PageRequests.of(page, size, sort, SORT, Sort.by(Sort.Order.desc("createdAt")));
    }

    // ── reads ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<PurchaseOrderSummary> list(User actor, PurchaseOrderStatus status, UUID centerId, UUID supplierId,
                                           java.time.LocalDate from, java.time.LocalDate to, Pageable pageable) {
        CenterScope.requireRole(actor, READ);
        DateRange range = DateRange.optional(from, to);
        Specification<PurchaseOrder> spec = CenterScope.of(actor).spec();
        if (status != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        if (centerId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("center").get("id"), centerId));
        if (supplierId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("supplier").get("id"), supplierId));
        if (range != null) {
            spec = spec.and((r, q, cb) -> {
                Predicate a = cb.greaterThanOrEqualTo(r.get("createdAt"), range.start());
                Predicate b = cb.lessThan(r.get("createdAt"), range.endExclusive());
                return cb.and(a, b);
            });
        }
        return repo.findAll(spec, pageable).map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(User actor, UUID id) {
        CenterScope.requireRole(actor, READ);
        return toDetail(load(id, actor, false));
    }

    // ── draft ────────────────────────────────────────────────

    @Transactional
    public PurchaseOrderResponse create(User actor, PurchaseOrderRequest req) {
        CenterScope.requireRole(actor, EDIT);
        Center center = tenantSecurity.requireCenter(req.centerId());
        CenterScope.of(actor).requireContains(center, "Center");
        UUID tenant = center.getTenantId();
        if (tenant == null) throw new IllegalArgumentException("Center has no tenant assigned");

        PurchaseOrder po = new PurchaseOrder();
        po.setTenantId(tenant);
        po.setCenter(center);
        po.setCreatedBy(actor);
        applyHeader(po, req, actor, center);
        po = repo.saveAndFlush(po);
        em.refresh(po); // po_number comes from the V36 trigger
        replaceLines(po, req.lines(), center);
        po = repo.save(po);
        log.info("AUDIT purchase order created: id={} number={} actor={}", po.getId(), po.getPoNumber(), actor.getId());
        return toDetail(po);
    }

    @Transactional
    public PurchaseOrderResponse update(User actor, UUID id, PurchaseOrderRequest req) {
        CenterScope.requireRole(actor, EDIT);
        PurchaseOrder po = load(id, actor, true);
        requireStatus(po, PurchaseOrderStatus.draft, "Only a draft purchase order can be edited");
        checkVersion(po, req.version());
        if (!po.getCenter().getId().equals(req.centerId())) {
            throw new IllegalArgumentException("centerId cannot be changed");
        }
        applyHeader(po, req, actor, po.getCenter());
        po.getLines().clear();
        repo.flush(); // delete old lines before inserting the new ones (unique line/item keys)
        replaceLines(po, req.lines(), po.getCenter());
        log.info("AUDIT purchase order edited: id={} actor={}", id, actor.getId());
        return toDetail(repo.save(po));
    }

    // ── workflow ─────────────────────────────────────────────

    @Transactional
    public PurchaseOrderResponse submit(User actor, UUID id, Long version) {
        CenterScope.requireRole(actor, EDIT);
        PurchaseOrder po = load(id, actor, true);
        checkVersion(po, version);
        if (po.getLines().isEmpty()) throw new WorkflowException("Purchase order has no lines");
        po.transitionTo(PurchaseOrderStatus.submitted);
        po.setSubmittedAt(Instant.now());
        repo.save(po);
        notifier.toRoles(po.getTenantId(), po.getCenter().getId(),
            EnumSet.of(UserRole.operating_company_manager, UserRole.maintenance_manager), actor.getId(), null,
            "PO_SUBMITTED", "Purchase order " + po.getPoNumber() + " awaits approval");
        log.info("AUDIT purchase order submitted: id={} actor={}", id, actor.getId());
        return toDetail(po);
    }

    @Transactional
    public PurchaseOrderResponse approve(User actor, UUID id, String note, Long version) {
        return decide(actor, id, true, note, version);
    }

    @Transactional
    public PurchaseOrderResponse reject(User actor, UUID id, String reason, Long version) {
        return decide(actor, id, false, reason, version);
    }

    private PurchaseOrderResponse decide(User actor, UUID id, boolean approve, String text, Long version) {
        CenterScope.requireRole(actor, APPROVE);
        PurchaseOrder po = load(id, actor, true);
        checkVersion(po, version);
        // Segregation of duties: nobody decides on the order they created (DB CHECK is the backstop)
        if (po.getCreatedBy().getId().equals(actor.getId())) {
            throw new AccessDeniedException("The creator of a purchase order cannot approve or reject it");
        }
        if (approve) enforceThreshold(actor, po);
        po.transitionTo(approve ? PurchaseOrderStatus.approved : PurchaseOrderStatus.rejected);
        po.setApprovedBy(actor);
        po.setApprovedAt(Instant.now());
        po.setDecisionReason(InputSanitizer.clean(text, 1000));
        repo.save(po);
        notifier.toUser(po.getCreatedBy(), null, approve ? "PO_APPROVED" : "PO_REJECTED",
            "Purchase order " + po.getPoNumber() + (approve ? " was approved" : " was rejected"));
        log.info("AUDIT purchase order {}: id={} actor={}", approve ? "approved" : "rejected", id, actor.getId());
        return toDetail(po);
    }

    /** Orders above the configured limit need an operating_company_manager (or system_admin). */
    void enforceThreshold(User actor, PurchaseOrder po) {
        BigDecimal limit = props.getManagerApprovalLimit();
        if (limit != null && actor.getRole() == UserRole.maintenance_manager && po.total().compareTo(limit) > 0) {
            throw new AccessDeniedException("Orders above " + limit.toPlainString()
                + " must be approved by the operating company manager");
        }
    }

    @Transactional
    public PurchaseOrderResponse cancel(User actor, UUID id, String reason, Long version) {
        PurchaseOrder po = load(id, actor, true);
        if (po.getStatus() == PurchaseOrderStatus.draft) CenterScope.requireRole(actor, merge(EDIT, CANCEL_ANY));
        else CenterScope.requireRole(actor, CANCEL_ANY);
        checkVersion(po, version);
        po.transitionTo(PurchaseOrderStatus.cancelled);
        po.setDecisionReason(InputSanitizer.clean(reason, 1000));
        repo.save(po);
        log.info("AUDIT purchase order cancelled: id={} actor={}", id, actor.getId());
        return toDetail(po);
    }

    /** Short-close: stops waiting for the rest of an approved / partially received order. */
    @Transactional
    public PurchaseOrderResponse close(User actor, UUID id, String reason, Long version) {
        CenterScope.requireRole(actor, CLOSE);
        PurchaseOrder po = load(id, actor, true);
        checkVersion(po, version);
        po.transitionTo(PurchaseOrderStatus.closed);
        po.setDecisionReason(InputSanitizer.clean(reason, 1000));
        repo.save(po);
        log.info("AUDIT purchase order closed: id={} actor={}", id, actor.getId());
        return toDetail(po);
    }

    // ── receiving ────────────────────────────────────────────

    /** Idempotent goods receipt. Not transactional itself: the idempotency service owns the transaction. */
    public Outcome<ReceiptResponse> receive(User actor, UUID poId, ReceiveRequest req, String idemKey) {
        CenterScope.requireRole(actor, RECEIVE);
        String fp = IdempotencyService.fingerprint(mapper, req, poId);
        return idempotency.execute(actor, "po.receive", idemKey, fp,
            recordId -> {
                PoReceipt r = doReceive(actor, poId, req, recordId);
                return new Result<>(toReceipt(r), r.getId());
            },
            resultRef -> toReceipt(receiptRepo.findById(resultRef).orElseThrow(
                () -> new ResourceNotFoundException("Receipt not found"))));
    }

    PoReceipt doReceive(User actor, UUID poId, ReceiveRequest req, UUID idemRecordId) {
        PurchaseOrder po = load(poId, actor, true);
        if (!po.getStatus().canReceive()) {
            throw new WorkflowException("Purchase order in status " + po.getStatus() + " cannot receive goods");
        }
        ledger.requireWarehouse(po.getWarehouseId(), actor, StockLedger.Access.OPERATE);

        Map<UUID, PurchaseOrderLine> byId = new HashMap<>();
        for (PurchaseOrderLine l : po.getLines()) byId.put(l.getId(), l);
        Set<UUID> seen = new HashSet<>();
        for (ReceiveLineRequest l : req.lines()) {
            if (!seen.add(l.poLineId())) throw new IllegalArgumentException("Duplicate poLineId in receipt");
            if (!byId.containsKey(l.poLineId())) throw new ResourceNotFoundException("Purchase order line not found");
            Quantities.require(l.quantity(), "quantity");
        }

        PoReceipt receipt = new PoReceipt();
        receipt.setTenantId(po.getTenantId());
        receipt.setPo(po);
        receipt.setWarehouseId(po.getWarehouseId());
        receipt.setReceivedBy(actor);
        receipt.setNote(InputSanitizer.clean(req.note(), 1000));
        receipt.setIdempotencyRecordId(idemRecordId);
        receipt = receiptRepo.saveAndFlush(receipt);

        List<StockCommand> cmds = new ArrayList<>();
        List<PoReceiptLine> rlines = new ArrayList<>();
        for (ReceiveLineRequest l : req.lines()) {
            PurchaseOrderLine pl = byId.get(l.poLineId());
            pl.receive(l.quantity()); // 422 on over-receipt
            PoReceiptLine rl = new PoReceiptLine();
            rl.setTenantId(po.getTenantId());
            rl.setReceipt(receipt);
            rl.setPoLineId(pl.getId());
            rl.setQuantity(l.quantity());
            receipt.getLines().add(rl);
            rlines.add(rl);
        }
        receipt = receiptRepo.saveAndFlush(receipt); // receipt lines get ids and rows (ledger FK)
        for (PoReceiptLine rl : receipt.getLines()) {
            PurchaseOrderLine pl = byId.get(rl.getPoLineId());
            cmds.add(new StockCommand(po.getTenantId(), po.getWarehouseId(), pl.getItemId(), MovementKind.receipt,
                rl.getQuantity(), false, po.getWorkOrderId(), null, rl.getId(), null, null,
                receipt.getNote(), po.getPoNumber(), actor, idemRecordId));
        }
        List<MovementRef> moved = ledger.apply(cmds);
        Map<UUID, UUID> movementByItem = new HashMap<>();
        for (MovementRef m : moved) movementByItem.put(m.itemId(), m.id());
        for (PoReceiptLine rl : receipt.getLines()) {
            rl.setMovementId(movementByItem.get(byId.get(rl.getPoLineId()).getItemId()));
        }
        po.transitionOrStay(po.fullyReceived() ? PurchaseOrderStatus.received : PurchaseOrderStatus.partially_received);
        repo.saveAndFlush(po);
        receiptRepo.saveAndFlush(receipt);
        log.info("AUDIT purchase order received: id={} receipt={} actor={}", poId, receipt.getId(), actor.getId());
        return receipt;
    }

    // ── helpers ──────────────────────────────────────────────

    /** Loads with scope check (404 outside), optionally with the header row lock. */
    PurchaseOrder load(UUID id, User actor, boolean lock) {
        PurchaseOrder po = null;
        if (id != null) {
            po = lock ? em.find(PurchaseOrder.class, id, LockModeType.PESSIMISTIC_WRITE, LOCK_HINT)
                      : em.find(PurchaseOrder.class, id);
        }
        if (po == null || !CenterScope.tenantMatches(po.getTenantId()) || !CenterScope.of(actor).contains(po.getCenter())) {
            throw new ResourceNotFoundException("Purchase order not found");
        }
        return po;
    }

    private void applyHeader(PurchaseOrder po, PurchaseOrderRequest req, User actor, Center center) {
        Supplier supplier = suppliers.require(req.supplierId());
        if (!po.getTenantId().equals(supplier.getTenantId())) throw new ResourceNotFoundException("Supplier not found");
        if (!supplier.isActive()) throw new WorkflowException("Supplier is inactive");
        ledger.requireWarehouse(req.warehouseId(), actor, StockLedger.Access.OPERATE);
        RefLookup.WarehouseInfo wh = lookup.warehouse(req.warehouseId())
            .filter(w -> w.tenantId().equals(po.getTenantId()))
            .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found"));
        if (!wh.centerId().equals(center.getId())) throw new WorkflowException("Warehouse belongs to another center");
        if (!wh.active()) throw new WorkflowException("Warehouse is inactive");
        if (req.workOrderId() != null) {
            WorkOrder wo = woAccess.requireReadable(req.workOrderId(), actor);
            if (!po.getTenantId().equals(wo.getTenantId()) || !center.getId().equals(wo.getCenter().getId())) {
                throw new WorkflowException("Work order belongs to another center");
            }
        }
        if (req.materialRequestId() != null) {
            MaterialRequest mr = mrRepo.findById(req.materialRequestId())
                .filter(m -> po.getTenantId().equals(m.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Material request not found"));
            if (!mr.getCenter().getId().equals(center.getId())) {
                throw new WorkflowException("Material request belongs to another center");
            }
        }
        po.setSupplier(supplier);
        po.setWarehouseId(req.warehouseId());
        po.setWorkOrderId(req.workOrderId());
        po.setMaterialRequestId(req.materialRequestId());
        po.setExpectedDate(req.expectedDate());
        po.setNotes(InputSanitizer.clean(req.notes(), 2000));
    }

    private void replaceLines(PurchaseOrder po, List<PoLineRequest> lines, Center center) {
        Set<UUID> items = new HashSet<>();
        int no = 1;
        for (PoLineRequest l : lines) {
            if (!items.add(l.itemId())) throw new IllegalArgumentException("Duplicate item in purchase order");
            Quantities.require(l.qtyOrdered(), "qtyOrdered");
            ledger.requireItem(l.itemId(), center.getId());
            PurchaseOrderLine pl = new PurchaseOrderLine();
            pl.setTenantId(po.getTenantId());
            pl.setPo(po);
            pl.setLineNo(no++);
            pl.setItemId(l.itemId());
            pl.setQtyOrdered(l.qtyOrdered());
            pl.setUnitPrice(l.unitPrice());
            po.getLines().add(pl);
        }
    }

    private static void requireStatus(PurchaseOrder po, PurchaseOrderStatus expected, String msg) {
        if (po.getStatus() != expected) throw new WorkflowException(msg);
    }

    private static void checkVersion(PurchaseOrder po, Long version) {
        if (version != null && !version.equals(po.getVersion())) {
            throw new ConflictException("Purchase order was modified concurrently, reload");
        }
    }

    private static UserRole[] merge(UserRole[] a, UserRole[] b) {
        Set<UserRole> s = new LinkedHashSet<>(Arrays.asList(a));
        s.addAll(Arrays.asList(b));
        return s.toArray(new UserRole[0]);
    }

    // ── mapping ──────────────────────────────────────────────

    private PurchaseOrderSummary toSummary(PurchaseOrder po) {
        return new PurchaseOrderSummary(po.getId(), po.getPoNumber(), po.getStatus(),
            RefLookup.centerRef(po.getCenter()), supplierRef(po.getSupplier()), po.getWarehouseId(),
            po.getExpectedDate(), po.total(), po.getCreatedAt());
    }

    private static SupplierRef supplierRef(Supplier s) {
        return new SupplierRef(s.getId(), s.getCode(), s.getName(), s.getNameAr());
    }

    PurchaseOrderResponse toDetail(PurchaseOrder po) {
        Map<UUID, ItemRef> items = lookup.items(po.getLines().stream().map(PurchaseOrderLine::getItemId).toList());
        List<PoLineResponse> lines = po.getLines().stream().map(l -> new PoLineResponse(l.getId(), l.getLineNo(),
            items.get(l.getItemId()), l.getQtyOrdered(), l.getQtyReceived(), l.getUnitPrice())).toList();
        List<ReceiptResponse> receipts = receiptRepo.findByPoIdOrderByReceivedAtAsc(po.getId()).stream()
            .map(this::toReceipt).toList();
        return new PurchaseOrderResponse(po.getId(), po.getPoNumber(), po.getStatus(),
            RefLookup.centerRef(po.getCenter()), supplierRef(po.getSupplier()),
            lookup.warehouse(po.getWarehouseId()).map(RefLookup.WarehouseInfo::ref).orElse(null),
            po.getWorkOrderId(), po.getMaterialRequestId(), po.getExpectedDate(), po.getNotes(),
            RefLookup.userRef(po.getCreatedBy()), po.getSubmittedAt(), RefLookup.userRef(po.getApprovedBy()),
            po.getApprovedAt(), po.getDecisionReason(), po.total(), lines, receipts, po.getVersion());
    }

    ReceiptResponse toReceipt(PoReceipt r) {
        return new ReceiptResponse(r.getId(), r.getPo().getId(), r.getReceivedAt(),
            RefLookup.userRef(r.getReceivedBy()), r.getNote(),
            r.getLines().stream().map(l -> new ReceiptLineResponse(l.getPoLineId(), l.getQuantity(), l.getMovementId())).toList());
    }
}
