package com.smms.inventory.service;

import com.smms.entity.InventoryItem;
import com.smms.entity.InventoryMovement;
import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.InventoryMovementType;
import com.smms.enums.UserRole;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.inventory.InventoryProperties;
import com.smms.inventory.api.InsufficientStockException;
import com.smms.inventory.api.StockLedger;
import com.smms.inventory.entity.StockBalance;
import com.smms.inventory.entity.Warehouse;
import com.smms.inventory.repo.InventoryItemRepository;
import com.smms.inventory.repo.InventoryMovementRepository;
import com.smms.inventory.repo.StockBalanceRepository;
import com.smms.inventory.repo.WarehouseRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * The stock ledger. Every method joins the caller's transaction (MANDATORY).
 *
 * <p>Concurrency: the balance rows an operation touches are locked with SELECT ... FOR UPDATE in
 * ascending (item, warehouse) order, under a transaction-scoped lock_timeout. Two operations that
 * touch the same rows in any order therefore queue instead of deadlocking. Availability is checked
 * AFTER the lock is held, so concurrent issues cannot both pass on a stale read. The CHECK
 * constraints of stock_balances are only the backstop.
 */
@Service
@RequiredArgsConstructor
public class StockLedgerService implements StockLedger {

    private final WarehouseRepository warehouses;
    private final InventoryItemRepository items;
    private final StockBalanceRepository balances;
    private final InventoryMovementRepository movements;
    private final InventoryAccess access;
    private final InventoryProperties props;
    private final ApplicationEventPublisher events;
    @PersistenceContext private EntityManager em;

    private static final Set<UserRole> NO_WAREHOUSE_ACCESS = Set.of(UserRole.resident, UserRole.department, UserRole.other);

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WarehouseRef requireWarehouse(UUID warehouseId, User actor, Access mode) {
        Warehouse w = loadWarehouse(warehouseId, actor);
        if (mode == Access.OPERATE) access.requireRole(actor, InventoryAccess.OPERATORS);
        else if (actor.getRole() == null || NO_WAREHOUSE_ACCESS.contains(actor.getRole())) {
            throw new AccessDeniedException("Role not permitted");
        }
        return new WarehouseRef(w.getId(), w.getCode(), w.getName(), w.getNameAr());
    }

    /** Warehouse in scope or 404; no role check. */
    public Warehouse loadWarehouse(UUID warehouseId, User actor) {
        Warehouse w = warehouseId == null ? null : warehouses.findById(warehouseId).filter(x -> x.getDeletedAt() == null).orElse(null);
        if (w == null || !access.tenantVisible(w) || !access.centerInScope(actor, w.getCenter())) {
            throw new ResourceNotFoundException("Warehouse not found");
        }
        return w;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ItemRef requireItem(UUID itemId, UUID centerId) {
        InventoryItem i = itemId == null ? null : items.findById(itemId).filter(x -> x.getDeletedAt() == null).orElse(null);
        if (i == null || !i.isActive() || centerId == null || !centerId.equals(i.getCenter().getId())
            || !access.tenantVisible(i)) {
            throw new ResourceNotFoundException("Item not found");
        }
        return itemRef(i);
    }

    static ItemRef itemRef(InventoryItem i) {
        String uom = i.getUom() != null ? i.getUom().getCode() : i.getUnit();
        return new ItemRef(i.getId(), i.getSku(), i.getName(), i.getNameAr(), uom);
    }

    private record Pair(UUID itemId, UUID warehouseId) implements Comparable<Pair> {
        @Override public int compareTo(Pair o) {
            int c = itemId.compareTo(o.itemId);
            return c != 0 ? c : warehouseId.compareTo(o.warehouseId);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MovementRef> apply(List<StockCommand> cmds) {
        if (cmds == null || cmds.isEmpty()) return List.of();
        Map<UUID, Warehouse> whs = new HashMap<>();
        Map<UUID, InventoryItem> its = new HashMap<>();
        TreeSet<Pair> pairs = new TreeSet<>();
        Set<Pair> needsRow = new HashSet<>();
        for (StockCommand c : cmds) {
            validate(c);
            Warehouse w = whs.computeIfAbsent(c.warehouseId(), id -> warehouses.findById(id)
                .filter(x -> x.getDeletedAt() == null).orElseThrow(() -> new ResourceNotFoundException("Warehouse not found")));
            InventoryItem it = its.computeIfAbsent(c.itemId(), id -> items.findById(id)
                .filter(x -> x.getDeletedAt() == null).orElseThrow(() -> new ResourceNotFoundException("Item not found")));
            // tenant and center consistency of the three parts (defence in depth: callers check scope first)
            if (!c.tenantId().equals(w.getTenantId()) || !c.tenantId().equals(it.getTenantId())) {
                throw new ResourceNotFoundException("Item or warehouse not found");
            }
            if (!w.getCenter().getId().equals(it.getCenter().getId())) {
                throw new IllegalArgumentException("Item and warehouse belong to different centers");
            }
            boolean reversal = isReversal(c.kind());
            if (!reversal && (!it.isActive() || !w.isActive())) {
                throw new WorkflowException(!w.isActive() ? "Warehouse is inactive" : "Item is inactive");
            }
            QuantityRules.validate(c.qty(), it.getUom() == null ? null : (int) it.getUom().getDecimals());
            Pair p = new Pair(c.itemId(), c.warehouseId());
            pairs.add(p);
            if (c.kind().isInbound()) needsRow.add(p);
        }

        setLockTimeout();
        // rows that an inbound command needs are created first (idempotent), then everything is locked in order
        for (Pair p : pairs) {
            if (needsRow.contains(p)) {
                em.createNativeQuery("INSERT INTO stock_balances (id, tenant_id, item_id, warehouse_id) "
                        + "VALUES (:id, :t, :i, :w) ON CONFLICT (item_id, warehouse_id) DO NOTHING")
                    .setParameter("id", UUID.randomUUID()).setParameter("t", its.get(p.itemId()).getTenantId())
                    .setParameter("i", p.itemId()).setParameter("w", p.warehouseId()).executeUpdate();
            }
        }
        Map<Pair, StockBalance> locked = new HashMap<>();
        for (Pair p : pairs) {
            balances.lockByItemAndWarehouse(p.itemId(), p.warehouseId()).ifPresent(b -> locked.put(p, b));
        }

        List<MovementRef> out = new ArrayList<>();
        for (StockCommand c : cmds) {
            Pair p = new Pair(c.itemId(), c.warehouseId());
            InventoryItem it = its.get(c.itemId());
            StockBalance b = locked.get(p);
            BigDecimal qty = c.qty().setScale(QuantityRules.MAX_DECIMALS);
            if (isReversal(c.kind())) checkReversal(c);
            BigDecimal onHand = b == null ? BigDecimal.ZERO : b.getOnHand();
            BigDecimal reserved = b == null ? BigDecimal.ZERO : b.getReserved();
            if (c.kind().isInbound()) {
                b.setOnHand(onHand.add(qty));
            } else if (c.consumeReservation()) {
                // issue against a reservation: the whole quantity must be reserved (callers split otherwise)
                if (c.kind() != MovementKind.issue) throw new IllegalArgumentException("consumeReservation applies to issue only");
                if (b == null || reserved.compareTo(qty) < 0) throw new InsufficientStockException(it.getSku(), reserved, qty);
                b.setOnHand(onHand.subtract(qty));
                b.setReserved(reserved.subtract(qty));
            } else {
                BigDecimal available = onHand.subtract(reserved);
                // checked under the row lock: a concurrent issue cannot have taken this stock meanwhile
                if (b == null || available.compareTo(qty) < 0) throw new InsufficientStockException(it.getSku(), available.max(BigDecimal.ZERO), qty);
                b.setOnHand(onHand.subtract(qty));
            }
            BigDecimal after = b.getOnHand();
            InventoryMovement m = InventoryMovement.builder()
                .item(it).warehouse(whs.get(c.warehouseId()))
                .workOrder(c.workOrderId() == null ? null : em.getReference(WorkOrder.class, c.workOrderId()))
                .actor(c.actor() == null ? null : em.getReference(User.class, c.actor().getId()))
                .type(c.kind().isInbound() ? InventoryMovementType.stock_in : InventoryMovementType.stock_out)
                .movementKind(c.kind()).quantity(qty).balanceAfter(after)
                .reasonCode(c.reasonCode()).reversalOfId(c.reversalOfId())
                .materialRequestLineId(c.materialRequestLineId()).poReceiptLineId(c.poReceiptLineId())
                .reference(c.reference()).idempotencyRecordId(c.idempotencyRecordId()).note(c.note())
                .build();
            m.setTenantId(c.tenantId());
            movements.save(m);
            out.add(new MovementRef(m.getId(), c.kind(), c.itemId(), c.warehouseId(), qty, after));
        }
        em.flush(); // constraint violations (backstop) surface here, inside the transaction
        events.publishEvent(new StockChangedEvent(Set.copyOf(its.keySet())));
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID warehouseId, UUID itemId, BigDecimal qty) {
        qty = QuantityRules.validate(qty, null);
        setLockTimeout();
        StockBalance b = balances.lockByItemAndWarehouse(itemId, warehouseId).orElse(null);
        BigDecimal available = b == null ? BigDecimal.ZERO : b.available();
        if (b == null || available.compareTo(qty) < 0) {
            throw new InsufficientStockException(skuOf(itemId), available, qty);
        }
        b.setReserved(b.getReserved().add(qty));
        em.flush();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID warehouseId, UUID itemId, BigDecimal qty) {
        qty = QuantityRules.validate(qty, null);
        setLockTimeout();
        StockBalance b = balances.lockByItemAndWarehouse(itemId, warehouseId).orElse(null);
        if (b == null || b.getReserved().compareTo(qty) < 0) {
            throw new ConflictException("Cannot release more than is reserved for item " + skuOf(itemId));
        }
        b.setReserved(b.getReserved().subtract(qty));
        em.flush();
    }

    private String skuOf(UUID itemId) {
        return items.findById(itemId).map(InventoryItem::getSku).orElse(String.valueOf(itemId));
    }

    private void setLockTimeout() {
        em.createNativeQuery("select set_config('lock_timeout', :v, true)")
            .setParameter("v", props.getLockTimeoutMs() + "ms").getSingleResult();
    }

    private static boolean isReversal(MovementKind k) {
        return k == MovementKind.reversal_in || k == MovementKind.reversal_out;
    }

    private static void validate(StockCommand c) {
        if (c.tenantId() == null || c.warehouseId() == null || c.itemId() == null || c.kind() == null) {
            throw new IllegalArgumentException("tenant, warehouse, item and kind are required");
        }
        boolean correction = c.kind() == MovementKind.adjustment_in || c.kind() == MovementKind.adjustment_out || isReversal(c.kind());
        if (correction && (c.reasonCode() == null || c.reasonCode().isBlank() || c.note() == null || c.note().trim().length() < 5)) {
            throw new IllegalArgumentException("A reason code and a note of at least 5 characters are required");
        }
        if (isReversal(c.kind()) != (c.reversalOfId() != null)) {
            throw new IllegalArgumentException("reversalOfId is required for reversals and only for reversals");
        }
        if (c.consumeReservation() && c.kind() != MovementKind.issue) {
            throw new IllegalArgumentException("consumeReservation applies to issue only");
        }
    }

    /** Called with the balance lock held: exactness and uniqueness of the reversal. */
    private void checkReversal(StockCommand c) {
        InventoryMovement orig = movements.findById(c.reversalOfId())
            .orElseThrow(() -> new ResourceNotFoundException("Movement not found"));
        if (!c.tenantId().equals(orig.getTenantId())) throw new ResourceNotFoundException("Movement not found");
        if (orig.getMovementKind() == null || isReversal(orig.getMovementKind())) {
            throw new WorkflowException("A reversal or legacy movement cannot be reversed");
        }
        boolean sameSpot = orig.getItem().getId().equals(c.itemId()) && orig.getWarehouse() != null
            && orig.getWarehouse().getId().equals(c.warehouseId());
        boolean opposite = orig.getMovementKind().isInbound() == (c.kind() == MovementKind.reversal_out);
        if (!sameSpot || !opposite || orig.getQuantity().compareTo(c.qty()) != 0) {
            throw new IllegalArgumentException("A reversal must mirror the original movement exactly");
        }
        if (movements.existsByReversalOfId(orig.getId())) throw new ConflictException("Movement is already reversed");
    }
}
