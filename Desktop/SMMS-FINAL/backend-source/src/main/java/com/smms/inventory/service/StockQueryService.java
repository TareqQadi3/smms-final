package com.smms.inventory.service;

import com.smms.entity.InventoryItem;
import com.smms.entity.InventoryMovement;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.inventory.InventoryProperties;
import com.smms.inventory.api.MovementLinkProvider;
import com.smms.inventory.api.StockLedger.MovementKind;
import com.smms.inventory.dto.Dtos.*;
import com.smms.inventory.entity.StockBalance;
import com.smms.inventory.entity.Warehouse;
import com.smms.inventory.repo.*;
import com.smms.util.PageRequests;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read side of the ledger: balances, movement history, stock card, min-qty setting. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockQueryService {

    public static final Map<String, String> MOVEMENT_SORT = Map.of("createdAt", "createdAt", "quantity", "quantity", "kind", "movementKind");
    public static final Map<String, String> BALANCE_SORT = Map.of("onHand", "onHand", "reserved", "reserved", "sku", "item.sku", "warehouse", "warehouse.code");

    private final InventoryMovementRepository movements;
    private final StockBalanceRepository balances;
    private final InventoryItemRepository items;
    private final InventoryAccess access;
    private final InventoryProperties props;
    private final ObjectProvider<MovementLinkProvider> linkProviders;
    private final StockLedgerService ledger;

    // ── balances ─────────────────────────────────────────────

    public Page<BalanceResponse> balances(User actor, UUID warehouseId, UUID itemId, Boolean belowMin, int page, int size, List<String> sort) {
        access.requireRole(actor, InventoryAccess.STOCK_READERS);
        Pageable pg = PageRequests.of(page, size, sort, BALANCE_SORT, Sort.by("item.sku", "warehouse.code"));
        Specification<StockBalance> spec = (r, q, cb) -> {
            Join<?, ?> wh = r.join("warehouse");
            Join<?, ?> item = r.join("item");
            Join<?, ?> center = wh.join("center");
            List<Predicate> p = new ArrayList<>();
            p.add(access.scopePredicate(actor, cb, center, r.get("tenantId")));
            if (warehouseId != null) p.add(cb.equal(wh.get("id"), warehouseId));
            if (itemId != null) p.add(cb.equal(item.get("id"), itemId));
            if (belowMin != null && belowMin) {
                p.add(cb.and(cb.isNotNull(r.get("minQty")), cb.lessThanOrEqualTo(r.<BigDecimal>get("onHand"), r.<BigDecimal>get("minQty"))));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        return balances.findAll(spec, pg).map(b -> new BalanceResponse(b.getId(), itemRef(b.getItem()), whRef(b.getWarehouse()),
            b.getWarehouse().getCenter().getId(), b.getOnHand(), b.getReserved(), b.available(), b.getMinQty(),
            b.getMinQty() != null && b.getOnHand().compareTo(b.getMinQty()) <= 0));
    }

    /** Sets or clears the per-warehouse minimum. Locks the row (creating it empty) like any balance change. */
    @Transactional
    public BalanceResponse setMinQty(User actor, MinQtyRequest req) {
        access.requireRole(actor, InventoryAccess.CATALOG_WRITERS);
        Warehouse w = ledger.loadWarehouse(req.warehouseId(), actor);
        InventoryItem i = items.findById(req.itemId()).filter(x -> x.getDeletedAt() == null && access.tenantVisible(x)).orElse(null);
        if (i == null || !i.getCenter().getId().equals(w.getCenter().getId())) throw new ResourceNotFoundException("Item not found");
        if (req.minQty() != null) QuantityRulesForMin.check(req.minQty());
        balances.findByItemIdAndWarehouseId(i.getId(), w.getId()).orElseGet(() -> {
            // empty balance row so that the minimum can exist before the first receipt
            var b = StockBalance.builder().tenantId(w.getTenantId()).item(i).warehouse(w).build();
            return balances.saveAndFlush(b);
        });
        StockBalance b = balances.lockByItemAndWarehouse(i.getId(), w.getId()).orElseThrow();
        b.setMinQty(req.minQty() == null ? null : req.minQty().setScale(3));
        balances.saveAndFlush(b);
        return new BalanceResponse(b.getId(), itemRef(i), whRef(w), w.getCenter().getId(), b.getOnHand(), b.getReserved(),
            b.available(), b.getMinQty(), b.getMinQty() != null && b.getOnHand().compareTo(b.getMinQty()) <= 0);
    }

    static final class QuantityRulesForMin {
        static void check(BigDecimal v) {
            if (v.signum() < 0 || v.compareTo(QuantityRules.MAX) > 0 || Math.max(v.stripTrailingZeros().scale(), 0) > 3) {
                throw new IllegalArgumentException("minQty must be between 0 and 1000000 with at most 3 decimals");
            }
        }
    }

    // ── movements ────────────────────────────────────────────

    public Page<MovementResponse> movements(User actor, UUID itemId, UUID warehouseId, UUID workOrderId, MovementKind kind,
                                            LocalDate from, LocalDate to, int page, int size, List<String> sort) {
        requireMovementReader(actor);
        Instant[] range = range(from, to);
        Pageable pg = PageRequests.of(page, size, sort, MOVEMENT_SORT, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("seq")));
        return toResponses(movements.findAll(movementSpec(actor, itemId, warehouseId, workOrderId, kind, range), pg), pg);
    }

    /** Stock card of one item in one warehouse: opening balance, entries oldest first, closing balance. */
    public StockCardResponse stockCard(User actor, UUID itemId, UUID warehouseId, LocalDate from, LocalDate to, int page, int size) {
        access.requireRole(actor, InventoryAccess.STOCK_READERS);
        Warehouse w = ledger.loadWarehouse(warehouseId, actor);
        InventoryItem i = items.findById(itemId).filter(x -> x.getDeletedAt() == null && access.tenantVisible(x)
            && x.getCenter().getId().equals(w.getCenter().getId())).orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        Instant[] range = range(from, to);
        Pageable pg = PageRequests.of(page, size, null, Map.of(), Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("seq")));
        Page<MovementResponse> entries = toResponses(movements.findAll(
            movementSpec(actor, itemId, warehouseId, null, null, range).and((r, q, cb) -> cb.isNotNull(r.get("movementKind"))), pg), pg);
        BigDecimal opening = movements.balanceBefore(itemId, warehouseId, range[0]).orElse(BigDecimal.ZERO);
        BigDecimal closing = movements.balanceBefore(itemId, warehouseId, range[1]).orElse(BigDecimal.ZERO);
        ZoneId z = ZoneId.of(props.getZone());
        return new StockCardResponse(itemRef(i), whRef(w), range[0].atZone(z).toLocalDate(), range[1].atZone(z).toLocalDate().minusDays(1), opening, closing, entries);
    }

    private void requireMovementReader(User actor) {
        if (!access.has(actor, InventoryAccess.STOCK_READERS) && actor.getRole() != UserRole.technician) {
            throw new org.springframework.security.access.AccessDeniedException("Role not permitted");
        }
    }

    /** [from 00:00, to+1 day 00:00) in the configured zone; default last N days; validated. */
    Instant[] range(LocalDate from, LocalDate to) {
        ZoneId z = ZoneId.of(props.getZone());
        LocalDate t = to != null ? to : LocalDate.now(z);
        LocalDate f = from != null ? from : t.minusDays(props.getDefaultRangeDays() - 1L);
        if (f.isAfter(t)) throw new IllegalArgumentException("from must not be after to");
        if (ChronoUnit.DAYS.between(f, t) + 1 > props.getMaxRangeDays()) {
            throw new IllegalArgumentException("The date range must not exceed " + props.getMaxRangeDays() + " days");
        }
        return new Instant[] {f.atStartOfDay(z).toInstant(), t.plusDays(1).atStartOfDay(z).toInstant()};
    }

    private Specification<InventoryMovement> movementSpec(User actor, UUID itemId, UUID warehouseId, UUID workOrderId,
                                                           MovementKind kind, Instant[] range) {
        return (r, q, cb) -> {
            Join<?, ?> item = r.join("item");
            Join<?, ?> center = item.join("center");
            List<Predicate> p = new ArrayList<>();
            p.add(cb.isNull(r.get("deletedAt")));
            p.add(access.scopePredicate(actor, cb, center, r.get("tenantId")));
            if (actor.getRole() == UserRole.technician) {
                // technicians only see movements of the work orders assigned to them
                Join<?, ?> wo = r.join("workOrder", JoinType.INNER);
                p.add(cb.equal(wo.get("assignedTo").get("id"), actor.getId()));
            }
            if (itemId != null) p.add(cb.equal(item.get("id"), itemId));
            if (warehouseId != null) p.add(cb.equal(r.get("warehouse").get("id"), warehouseId));
            if (workOrderId != null) p.add(cb.equal(r.get("workOrder").get("id"), workOrderId));
            if (kind != null) p.add(cb.equal(r.get("movementKind"), kind));
            p.add(cb.greaterThanOrEqualTo(r.<Instant>get("createdAt"), range[0]));
            p.add(cb.lessThan(r.<Instant>get("createdAt"), range[1]));
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    /** Builds responses for a page, resolving reversals and links in two extra queries. */
    Page<MovementResponse> toResponses(Page<InventoryMovement> page, Pageable pg) {
        return new PageImpl<>(toResponses(page.getContent()), pg, page.getTotalElements());
    }

    public List<MovementResponse> toResponses(List<InventoryMovement> list) {
        if (list.isEmpty()) return List.of();
        List<UUID> ids = list.stream().map(InventoryMovement::getId).toList();
        Map<UUID, UUID> reversedBy = new HashMap<>();
        for (Object[] row : movements.reversalsOf(ids)) reversedBy.put((UUID) row[0], (UUID) row[1]);
        Map<UUID, MovementLinkProvider.Links> links = new HashMap<>();
        linkProviders.orderedStream().forEach(lp -> links.putAll(lp.resolve(ids)));
        List<MovementResponse> out = new ArrayList<>();
        for (InventoryMovement m : list) {
            var l = links.get(m.getId());
            var wo = m.getWorkOrder();
            User a = m.getActor();
            out.add(new MovementResponse(m.getId(), m.getMovementKind(), itemRef(m.getItem()),
                m.getWarehouse() == null ? null : whRef(m.getWarehouse()), m.getQuantity(), m.getBalanceAfter(),
                wo == null ? null : wo.getId(), wo == null ? null : wo.getTicketNumber(),
                l == null ? null : l.materialRequestId(), l == null ? null : l.poId(), m.getReversalOfId(), reversedBy.get(m.getId()),
                m.getReasonCode(), m.getNote(), m.getReference(),
                a == null ? null : new UserRef(a.getId(), a.getFullName(), a.getFullNameAr()), m.getCreatedAt()));
        }
        return out;
    }

    static ItemRefView itemRef(InventoryItem i) {
        return new ItemRefView(i.getId(), i.getSku(), i.getName(), i.getNameAr(), i.getUom() != null ? i.getUom().getCode() : i.getUnit());
    }

    static WarehouseRefView whRef(Warehouse w) {
        return new WarehouseRefView(w.getId(), w.getCode(), w.getName(), w.getNameAr());
    }
}
