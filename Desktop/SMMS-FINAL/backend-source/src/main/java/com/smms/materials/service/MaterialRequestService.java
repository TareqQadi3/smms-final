package com.smms.materials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.Center;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.TicketStatus;
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
import com.smms.inventory.api.StockLedger.StockCommand;
import com.smms.materials.dto.MaterialRequestDtos.*;
import com.smms.materials.entity.MaterialRequest;
import com.smms.materials.entity.MaterialRequestLine;
import com.smms.materials.entity.MaterialRequestStatus;
import com.smms.materials.repository.MaterialRequestRepository;
import com.smms.procurement.CenterScope;
import com.smms.procurement.Quantities;
import com.smms.procurement.dto.PurchaseOrderDtos.PoLineRequest;
import com.smms.procurement.dto.PurchaseOrderDtos.PurchaseOrderResponse;
import com.smms.procurement.service.ProcurementNotifier;
import com.smms.procurement.service.PurchaseOrderService;
import com.smms.procurement.service.RefLookup;
import com.smms.repository.WorkOrderRepository;
import com.smms.security.WorkOrderAccessPolicy;
import com.smms.util.InputSanitizer;
import com.smms.util.PageRequests;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.smms.workflow.WorkOrderHistoryWriter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Work-order material requests: submit, approve/reject, reserve, issue, consume (technician), return,
 * close, cancel. Stock moves only through {@link StockLedger}; the request header row is locked before
 * the ledger locks balances. Reserve, issue and return need an Idempotency-Key.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialRequestService {

    public static final UserRole[] CREATE = {UserRole.technician, UserRole.control_room, UserRole.maintenance_manager};
    public static final UserRole[] DECIDE = {UserRole.maintenance_manager, UserRole.operating_company_manager,
        UserRole.electrical_manager};
    public static final UserRole[] WAREHOUSE = {UserRole.warehouse};
    public static final UserRole[] CONSUME = {UserRole.technician};
    public static final UserRole[] CANCEL_STAFF = {UserRole.maintenance_manager, UserRole.warehouse, UserRole.system_admin};
    public static final UserRole[] CLOSE = {UserRole.warehouse, UserRole.maintenance_manager};
    public static final UserRole[] PO = {UserRole.warehouse, UserRole.maintenance_manager};
    public static final UserRole[] READ = {UserRole.system_admin, UserRole.region_supervisor, UserRole.hospital_supervisor,
        UserRole.maintenance_manager, UserRole.electrical_manager, UserRole.operating_company_manager,
        UserRole.warehouse, UserRole.control_room, UserRole.technician};
    private static final Set<UserRole> STAFF = EnumSet.of(UserRole.system_admin, UserRole.region_supervisor,
        UserRole.hospital_supervisor, UserRole.maintenance_manager, UserRole.electrical_manager,
        UserRole.operating_company_manager, UserRole.warehouse);
    public static final Map<String, String> SORT = Map.of("createdAt", "createdAt", "requestNumber", "requestNumber",
        "status", "status");
    private static final Map<String, Object> LOCK_HINT = Map.of("jakarta.persistence.lock.timeout", 5000);

    private final MaterialRequestRepository repo;
    private final WorkOrderRepository woRepo;
    private final WorkOrderAccessPolicy woAccess;
    private final WorkOrderHistoryWriter history;
    private final StockLedger ledger;
    private final RefLookup lookup;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;
    private final ProcurementNotifier notifier;
    private final PurchaseOrderService purchaseOrders;
    private final PlatformTransactionManager txManager;
    private final EntityManager em;

    public static Pageable pageable(int page, int size, List<String> sort) {
        return PageRequests.of(page, size, sort, SORT, Sort.by(Sort.Order.desc("createdAt")));
    }

    // ── reads ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<MaterialRequestSummary> list(User actor, MaterialRequestStatus status, UUID centerId,
                                             UUID warehouseId, Pageable pageable) {
        CenterScope.requireRole(actor, READ);
        Specification<MaterialRequest> spec = listScope(actor);
        if (status != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        if (centerId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("center").get("id"), centerId));
        if (warehouseId != null) spec = spec.and((r, q, cb) -> cb.equal(r.get("warehouseId"), warehouseId));
        return repo.findAll(spec, pageable).map(this::toSummary);
    }

    private Specification<MaterialRequest> listScope(User actor) {
        UUID tenant = com.smms.util.TenantContext.get();
        if (STAFF.contains(actor.getRole())) return CenterScope.of(actor).spec();
        UUID self = actor.getId();
        Specification<MaterialRequest> own = actor.getRole() == UserRole.technician
            ? (r, q, cb) -> cb.or(cb.equal(r.get("workOrder").get("assignedTo").get("id"), self),
                cb.equal(r.get("requestedBy").get("id"), self))
            : (r, q, cb) -> cb.equal(r.get("requestedBy").get("id"), self); // control room: own requests
        return tenant == null ? own : own.and((r, q, cb) -> cb.equal(r.get("tenantId"), tenant));
    }

    @Transactional(readOnly = true)
    public MaterialRequestResponse get(User actor, UUID id) {
        CenterScope.requireRole(actor, READ);
        return toResponse(loadReadable(id, actor, false));
    }

    @Transactional(readOnly = true)
    public List<MaterialRequestSummary> listForWorkOrder(User actor, UUID woId) {
        CenterScope.requireRole(actor, READ);
        WorkOrder wo = woAccess.requireReadable(woId, actor);
        Specification<MaterialRequest> spec = (r, q, cb) -> cb.and(
            cb.equal(r.get("workOrder").get("id"), wo.getId()), cb.equal(r.get("tenantId"), wo.getTenantId()));
        return repo.findAll(spec, Sort.by(Sort.Order.desc("createdAt"))).stream()
            .filter(m -> canRead(actor, m)).map(this::toSummary).toList();
    }

    // ── create ───────────────────────────────────────────────

    @Transactional
    public MaterialRequestResponse create(User actor, UUID woId, CreateRequest req) {
        CenterScope.requireRole(actor, CREATE);
        WorkOrder wo = woAccess.requireReadable(woId, actor);
        Center center = wo.getCenter();
        if (actor.getRole() == UserRole.technician) {
            if (wo.getAssignedTo() == null || !wo.getAssignedTo().getId().equals(actor.getId())) {
                throw new AccessDeniedException("Only the assigned technician can request materials");
            }
        } else {
            CenterScope.of(actor).requireContains(center, "WorkOrder");
        }
        if (wo.getStatus() != TicketStatus.IN_PROGRESS && wo.getStatus() != TicketStatus.WAITING_MATERIALS) {
            throw new WorkflowException("Materials can only be requested while the work order is in_progress or waiting_materials");
        }
        UUID tenant = wo.getTenantId();
        if (tenant == null) throw new IllegalArgumentException("Work order has no tenant assigned");
        RefLookup.WarehouseInfo wh = lookup.warehouse(req.warehouseId())
            .filter(w -> w.tenantId().equals(tenant) && w.centerId().equals(center.getId()))
            .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found"));
        if (!wh.active()) throw new WorkflowException("Warehouse is inactive");

        MaterialRequest mr = new MaterialRequest();
        mr.setTenantId(tenant);
        mr.setCenter(center);
        mr.setWorkOrder(wo);
        mr.setWarehouseId(wh.id());
        mr.setRequestedBy(actor);
        mr.setNote(InputSanitizer.clean(req.note(), 1000));
        mr = repo.saveAndFlush(mr);
        em.refresh(mr); // request_number comes from the V38 trigger

        Set<UUID> items = new HashSet<>();
        for (CreateLine l : req.lines()) {
            if (!items.add(l.itemId())) throw new IllegalArgumentException("Duplicate item in material request");
            Quantities.require(l.quantity(), "quantity");
            ledger.requireItem(l.itemId(), center.getId());
            MaterialRequestLine line = new MaterialRequestLine();
            line.setTenantId(tenant);
            line.setMr(mr);
            line.setItemId(l.itemId());
            line.setQtyRequested(l.quantity());
            mr.getLines().add(line);
        }
        mr = repo.saveAndFlush(mr);

        wo.setNeedsMaterials(true); // keeps the work order visible to the warehouse
        woRepo.save(wo);
        history.note(wo, actor, "Material request " + mr.getRequestNumber() + " submitted");
        notifier.toRoles(tenant, center.getId(), EnumSet.of(UserRole.maintenance_manager,
            UserRole.operating_company_manager, UserRole.electrical_manager), actor.getId(), wo,
            "MATERIAL_REQUEST_SUBMITTED", "Material request " + mr.getRequestNumber() + " awaits approval");
        log.info("AUDIT material request created: id={} wo={} actor={}", mr.getId(), woId, actor.getId());
        return toResponse(mr);
    }

    // ── decision ─────────────────────────────────────────────

    @Transactional
    public MaterialRequestResponse approve(User actor, UUID id, ApproveRequest req) {
        CenterScope.requireRole(actor, DECIDE);
        MaterialRequest mr = loadStaff(id, actor, true);
        checkVersion(mr, req == null ? null : req.version());
        requireStatus(mr, MaterialRequestStatus.submitted);
        Map<UUID, MaterialRequestLine> byId = lineMap(mr);
        if (req == null || req.lines() == null || req.lines().isEmpty()) {
            for (MaterialRequestLine l : mr.getLines()) l.approve(l.getQtyRequested());
        } else {
            Set<UUID> seen = new HashSet<>();
            for (MaterialRequestLine l : mr.getLines()) l.approve(BigDecimal.ZERO);
            for (ApproveLine a : req.lines()) {
                if (!seen.add(a.lineId())) throw new IllegalArgumentException("Duplicate lineId");
                MaterialRequestLine l = byId.get(a.lineId());
                if (l == null) throw new ResourceNotFoundException("Material request line not found");
                l.approve(Quantities.requireNonNegative(a.approvedQuantity(), "approvedQuantity"));
            }
        }
        if (mr.getLines().stream().noneMatch(l -> l.getQtyApproved().signum() > 0)) {
            throw new WorkflowException("Nothing approved: reject the request instead");
        }
        mr.transitionTo(MaterialRequestStatus.approved);
        decided(mr, actor, req == null ? null : req.note());
        repo.save(mr);
        notifier.toRoles(mr.getTenantId(), mr.getCenter().getId(), EnumSet.of(UserRole.warehouse), actor.getId(),
            mr.getWorkOrder(), "MATERIAL_REQUEST_APPROVED", "Material request " + mr.getRequestNumber() + " was approved");
        log.info("AUDIT material request approved: id={} actor={}", id, actor.getId());
        return toResponse(mr);
    }

    @Transactional
    public MaterialRequestResponse reject(User actor, UUID id, ReasonRequest req) {
        CenterScope.requireRole(actor, DECIDE);
        MaterialRequest mr = loadStaff(id, actor, true);
        checkVersion(mr, req.version());
        mr.transitionTo(MaterialRequestStatus.rejected);
        decided(mr, actor, req.reason());
        repo.save(mr);
        notifier.toUser(mr.getRequestedBy(), mr.getWorkOrder(), "MATERIAL_REQUEST_REJECTED",
            "Material request " + mr.getRequestNumber() + " was rejected");
        log.info("AUDIT material request rejected: id={} actor={}", id, actor.getId());
        return toResponse(mr);
    }

    private static void decided(MaterialRequest mr, User actor, String note) {
        mr.setDecidedBy(actor);
        mr.setDecidedAt(Instant.now());
        mr.setDecisionNote(InputSanitizer.clean(note, 1000));
    }

    // ── warehouse operations (idempotent) ────────────────────

    public Outcome<MaterialRequestResponse> reserve(User actor, UUID id, VersionRequest body, String key) {
        CenterScope.requireRole(actor, WAREHOUSE);
        return idempotent(actor, "mr.reserve", key, body == null ? new VersionRequest(null) : body, id,
            () -> doReserve(actor, id, body == null ? null : body.version()));
    }

    MaterialRequest doReserve(User actor, UUID id, Long version) {
        MaterialRequest mr = loadStaff(id, actor, true);
        checkVersion(mr, version);
        requireStatus(mr, MaterialRequestStatus.approved);
        ledger.requireWarehouse(mr.getWarehouseId(), actor, StockLedger.Access.OPERATE);
        for (MaterialRequestLine l : sortedLines(mr)) {
            BigDecimal delta = l.reserveAll();
            // a shortage raises InsufficientStockException (409) and rolls the whole reservation back
            if (delta.signum() > 0) ledger.reserve(mr.getWarehouseId(), l.getItemId(), delta);
        }
        mr.transitionTo(MaterialRequestStatus.reserved);
        repo.saveAndFlush(mr);
        log.info("AUDIT material request reserved: id={} actor={}", id, actor.getId());
        return mr;
    }

    public Outcome<MaterialRequestResponse> issue(User actor, UUID id, IssueRequest body, String key) {
        CenterScope.requireRole(actor, WAREHOUSE);
        return idempotent(actor, "mr.issue", key, body, id, () -> doIssue(actor, id, body));
    }

    MaterialRequest doIssue(User actor, UUID id, IssueRequest body) {
        MaterialRequest mr = loadStaff(id, actor, true);
        checkVersion(mr, body.version());
        if (mr.getStatus() != MaterialRequestStatus.approved && mr.getStatus() != MaterialRequestStatus.reserved
                && mr.getStatus() != MaterialRequestStatus.issued) {
            throw new WorkflowException("Material request in status " + mr.getStatus() + " cannot issue stock");
        }
        ledger.requireWarehouse(mr.getWarehouseId(), actor, StockLedger.Access.OPERATE);
        Map<UUID, BigDecimal> qty = quantities(mr, body.lines());
        List<StockCommand> cmds = new ArrayList<>();
        for (MaterialRequestLine l : sortedLines(mr)) {
            BigDecimal q = qty.get(l.getId());
            if (q == null) continue;
            BigDecimal fromReservation = l.issue(q); // 422 above the approved quantity
            if (fromReservation.signum() > 0) {
                cmds.add(cmd(mr, l, MovementKind.issue, fromReservation, true, actor, null));
            }
            BigDecimal direct = q.subtract(fromReservation);
            if (direct.signum() > 0) cmds.add(cmd(mr, l, MovementKind.issue, direct, false, actor, null));
        }
        ledger.apply(cmds);
        if (mr.getStatus() != MaterialRequestStatus.issued) mr.transitionTo(MaterialRequestStatus.issued);
        repo.saveAndFlush(mr);
        history.note(mr.getWorkOrder(), actor, "Materials issued for " + mr.getRequestNumber());
        notifier.toUser(mr.getRequestedBy(), mr.getWorkOrder(), "MATERIAL_REQUEST_ISSUED",
            "Materials for " + mr.getRequestNumber() + " were issued");
        log.info("AUDIT material request issued: id={} actor={}", id, actor.getId());
        return mr;
    }

    public Outcome<MaterialRequestResponse> giveBack(User actor, UUID id, ReturnRequest body, String key) {
        CenterScope.requireRole(actor, WAREHOUSE);
        return idempotent(actor, "mr.return", key, body, id, () -> doReturn(actor, id, body));
    }

    MaterialRequest doReturn(User actor, UUID id, ReturnRequest body) {
        MaterialRequest mr = loadStaff(id, actor, true);
        checkVersion(mr, body.version());
        requireStatus(mr, MaterialRequestStatus.issued);
        ledger.requireWarehouse(mr.getWarehouseId(), actor, StockLedger.Access.OPERATE);
        String reason = InputSanitizer.clean(body.reason(), 1000);
        Map<UUID, BigDecimal> qty = quantities(mr, body.lines());
        List<StockCommand> cmds = new ArrayList<>();
        for (MaterialRequestLine l : sortedLines(mr)) {
            BigDecimal q = qty.get(l.getId());
            if (q == null) continue;
            l.giveBack(q);
            cmds.add(cmd(mr, l, MovementKind.return_in, q, false, actor, reason));
        }
        ledger.apply(cmds);
        autoClose(mr);
        repo.saveAndFlush(mr);
        history.note(mr.getWorkOrder(), actor, "Materials returned for " + mr.getRequestNumber() + ": " + reason);
        log.info("AUDIT material request returned: id={} actor={}", id, actor.getId());
        return mr;
    }

    /** Technician records what was used (no stock movement: the stock left the warehouse at issue). */
    public Outcome<MaterialRequestResponse> consume(User actor, UUID id, ConsumeRequest body, String key) {
        CenterScope.requireRole(actor, CONSUME);
        if (key == null || key.isBlank()) {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            return new Outcome<>(tx.execute(s -> toResponse(doConsume(actor, id, body))), false);
        }
        return idempotent(actor, "mr.consume", key, body, id, () -> doConsume(actor, id, body));
    }

    MaterialRequest doConsume(User actor, UUID id, ConsumeRequest body) {
        MaterialRequest mr = loadReadable(id, actor, true);
        WorkOrder wo = mr.getWorkOrder();
        if (wo.getAssignedTo() == null || !wo.getAssignedTo().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Only the assigned technician can record consumption");
        }
        checkVersion(mr, body.version());
        requireStatus(mr, MaterialRequestStatus.issued);
        Map<UUID, BigDecimal> qty = quantities(mr, body.lines());
        for (MaterialRequestLine l : sortedLines(mr)) {
            BigDecimal q = qty.get(l.getId());
            if (q != null) l.consume(q); // 422 above what is outstanding
        }
        autoClose(mr);
        repo.saveAndFlush(mr);
        history.note(wo, actor, "Materials consumed for " + mr.getRequestNumber()
            + (body.note() == null || body.note().isBlank() ? "" : ": " + body.note()));
        log.info("AUDIT material request consumed: id={} actor={}", id, actor.getId());
        return mr;
    }

    private void autoClose(MaterialRequest mr) {
        if (mr.readyToClose()) mr.transitionTo(MaterialRequestStatus.closed);
    }

    /** Warehouse/manager closes an issued request once nothing is outstanding; drops open reservations. */
    @Transactional
    public MaterialRequestResponse close(User actor, UUID id, VersionRequest body) {
        CenterScope.requireRole(actor, CLOSE);
        MaterialRequest mr = loadStaff(id, actor, true);
        checkVersion(mr, body == null ? null : body.version());
        requireStatus(mr, MaterialRequestStatus.issued);
        for (MaterialRequestLine l : mr.getLines()) {
            if (l.outstanding().signum() != 0) {
                throw new WorkflowException("Materials are still outstanding: consume or return them first");
            }
        }
        releaseReservations(mr);
        mr.transitionTo(MaterialRequestStatus.closed);
        repo.save(mr);
        log.info("AUDIT material request closed: id={} actor={}", id, actor.getId());
        return toResponse(mr);
    }

    // ── cancel ───────────────────────────────────────────────

    @Transactional
    public MaterialRequestResponse cancel(User actor, UUID id, ReasonRequest req) {
        MaterialRequest mr = loadReadable(id, actor, true);
        boolean ownWhileSubmitted = mr.getStatus() == MaterialRequestStatus.submitted
            && mr.getRequestedBy().getId().equals(actor.getId());
        if (!ownWhileSubmitted) {
            CenterScope.requireRole(actor, CANCEL_STAFF);
            if (!CenterScope.of(actor).contains(mr.getCenter())) throw new ResourceNotFoundException("Material request not found");
        }
        checkVersion(mr, req.version());
        MaterialRequestStatus s = mr.getStatus();
        if (s != MaterialRequestStatus.submitted && s != MaterialRequestStatus.approved && s != MaterialRequestStatus.reserved) {
            throw new WorkflowException("Material request in status " + s + " cannot be cancelled");
        }
        releaseReservations(mr);
        mr.transitionTo(MaterialRequestStatus.cancelled);
        mr.setCancelReason(InputSanitizer.clean(req.reason(), 1000));
        repo.save(mr);
        history.note(mr.getWorkOrder(), actor, "Material request " + mr.getRequestNumber() + " cancelled");
        log.info("AUDIT material request cancelled: id={} actor={}", id, actor.getId());
        return toResponse(mr);
    }

    private void releaseReservations(MaterialRequest mr) {
        for (MaterialRequestLine l : sortedLines(mr)) {
            BigDecimal r = l.releaseReservation();
            if (r.signum() > 0) ledger.release(mr.getWarehouseId(), l.getItemId(), r);
        }
    }

    // ── purchase order for the shortage ──────────────────────

    @Transactional
    public PurchaseOrderResponse createPurchaseOrder(User actor, UUID id, PurchaseOrderRequest req) {
        CenterScope.requireRole(actor, PO);
        MaterialRequest mr = loadStaff(id, actor, false);
        MaterialRequestStatus s = mr.getStatus();
        if (s != MaterialRequestStatus.approved && s != MaterialRequestStatus.reserved && s != MaterialRequestStatus.issued) {
            throw new WorkflowException("Only an approved material request can be covered by a purchase order");
        }
        List<PoLineRequest> lines = new ArrayList<>();
        for (MaterialRequestLine l : sortedLines(mr)) {
            BigDecimal shortage = shortage(l.toReserve(), lookup.available(mr.getWarehouseId(), l.getItemId()));
            if (shortage.signum() > 0) lines.add(new PoLineRequest(l.getItemId(), shortage, null));
        }
        if (lines.isEmpty()) throw new WorkflowException("No shortage: the approved quantities are available");
        return purchaseOrders.create(actor, new com.smms.procurement.dto.PurchaseOrderDtos.PurchaseOrderRequest(
            mr.getCenter().getId(), req.supplierId(), mr.getWarehouseId(), null,
            "Created from material request " + mr.getRequestNumber(), mr.getWorkOrder().getId(), mr.getId(), lines, null));
    }

    /** Unreserved open need minus what is available, never negative. */
    public static BigDecimal shortage(BigDecimal unreservedNeed, BigDecimal available) {
        return unreservedNeed.subtract(available.max(BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }

    // ── helpers ──────────────────────────────────────────────

    private Outcome<MaterialRequestResponse> idempotent(User actor, String scope, String key, Object body, UUID id,
                                                        java.util.function.Supplier<MaterialRequest> op) {
        String fp = IdempotencyService.fingerprint(mapper, body, id);
        return idempotency.execute(actor, scope, key, fp,
            recordId -> {
                MaterialRequest mr = op.get();
                return new Result<>(toResponse(mr), mr.getId());
            },
            ref -> toResponse(loadReadable(ref, actor, false)));
    }

    private StockCommand cmd(MaterialRequest mr, MaterialRequestLine l, MovementKind kind, BigDecimal q,
                             boolean consumeReservation, User actor, String note) {
        return new StockCommand(mr.getTenantId(), mr.getWarehouseId(), l.getItemId(), kind, q, consumeReservation,
            mr.getWorkOrder().getId(), l.getId(), null, null, null, note, mr.getRequestNumber(), actor, null);
    }

    /** lineId to quantity; unknown line 404, duplicate 400, quantity rules 400. */
    private Map<UUID, BigDecimal> quantities(MaterialRequest mr, List<LineQuantity> in) {
        Map<UUID, MaterialRequestLine> byId = lineMap(mr);
        Map<UUID, BigDecimal> out = new HashMap<>();
        for (LineQuantity l : in) {
            if (!byId.containsKey(l.lineId())) throw new ResourceNotFoundException("Material request line not found");
            if (out.put(l.lineId(), Quantities.require(l.quantity(), "quantity")) != null) {
                throw new IllegalArgumentException("Duplicate lineId");
            }
        }
        return out;
    }

    private static Map<UUID, MaterialRequestLine> lineMap(MaterialRequest mr) {
        Map<UUID, MaterialRequestLine> m = new HashMap<>();
        for (MaterialRequestLine l : mr.getLines()) m.put(l.getId(), l);
        return m;
    }

    /** Stable order by item id: the ledger locks balances in the same order across requests. */
    private static List<MaterialRequestLine> sortedLines(MaterialRequest mr) {
        return mr.getLines().stream().sorted(Comparator.comparing(MaterialRequestLine::getItemId)).toList();
    }

    private static void requireStatus(MaterialRequest mr, MaterialRequestStatus expected) {
        if (mr.getStatus() != expected) {
            throw new WorkflowException("Material request is " + mr.getStatus() + ", expected " + expected);
        }
    }

    private static void checkVersion(MaterialRequest mr, Long version) {
        if (version != null && !version.equals(mr.getVersion())) {
            throw new ConflictException("Material request was modified concurrently, reload");
        }
    }

    private MaterialRequest find(UUID id, boolean lock) {
        if (id == null) return null;
        return lock ? em.find(MaterialRequest.class, id, LockModeType.PESSIMISTIC_WRITE, LOCK_HINT)
                    : em.find(MaterialRequest.class, id);
    }

    /** Staff operation: center/region scope of the actor (404 outside), optional header lock. */
    MaterialRequest loadStaff(UUID id, User actor, boolean lock) {
        MaterialRequest mr = find(id, lock);
        if (mr == null || !CenterScope.tenantMatches(mr.getTenantId()) || !CenterScope.of(actor).contains(mr.getCenter())) {
            throw new ResourceNotFoundException("Material request not found");
        }
        return mr;
    }

    /** Any reader (staff by scope, technician/control room through the work order), 404 otherwise. */
    MaterialRequest loadReadable(UUID id, User actor, boolean lock) {
        MaterialRequest mr = find(id, lock);
        if (mr == null || !CenterScope.tenantMatches(mr.getTenantId()) || !canRead(actor, mr)) {
            throw new ResourceNotFoundException("Material request not found");
        }
        return mr;
    }

    boolean canRead(User actor, MaterialRequest mr) {
        if (actor == null || actor.getRole() == null) return false;
        if (STAFF.contains(actor.getRole())) return CenterScope.of(actor).contains(mr.getCenter());
        if (actor.getRole() == UserRole.technician) {
            WorkOrder wo = mr.getWorkOrder();
            return (wo.getAssignedTo() != null && wo.getAssignedTo().getId().equals(actor.getId()))
                || mr.getRequestedBy().getId().equals(actor.getId());
        }
        if (actor.getRole() == UserRole.control_room) {
            if (mr.getRequestedBy().getId().equals(actor.getId())) return true;
            try {
                woAccess.requireReadable(mr.getWorkOrder().getId(), actor);
                return true;
            } catch (ResourceNotFoundException e) {
                return false;
            }
        }
        return false;
    }

    // ── mapping ──────────────────────────────────────────────

    private MaterialRequestSummary toSummary(MaterialRequest m) {
        return new MaterialRequestSummary(m.getId(), m.getRequestNumber(), m.getStatus(), woSummary(m.getWorkOrder()),
            m.getWarehouseId(), RefLookup.centerRef(m.getCenter()), RefLookup.userRef(m.getRequestedBy()), m.getCreatedAt());
    }

    private static WorkOrderSummary woSummary(WorkOrder wo) {
        return new WorkOrderSummary(wo.getId(), wo.getTicketNumber(), wo.getStatus());
    }

    MaterialRequestResponse toResponse(MaterialRequest m) {
        Map<UUID, ItemRef> items = lookup.items(m.getLines().stream().map(MaterialRequestLine::getItemId).toList());
        List<LineResponse> lines = m.getLines().stream().sorted(Comparator.comparing(MaterialRequestLine::getItemId))
            .map(l -> new LineResponse(l.getId(), items.get(l.getItemId()), l.getQtyRequested(), l.getQtyApproved(),
                l.getQtyReserved(), l.getQtyIssued(), l.getQtyConsumed(), l.getQtyReturned())).toList();
        return new MaterialRequestResponse(m.getId(), m.getRequestNumber(), m.getStatus(), woSummary(m.getWorkOrder()),
            lookup.warehouse(m.getWarehouseId()).map(RefLookup.WarehouseInfo::ref).orElse(null),
            RefLookup.centerRef(m.getCenter()), RefLookup.userRef(m.getRequestedBy()), RefLookup.userRef(m.getDecidedBy()),
            m.getDecidedAt(), m.getDecisionNote(), m.getNote(), m.getCancelReason(), lines, m.getVersion(), m.getCreatedAt());
    }
}
