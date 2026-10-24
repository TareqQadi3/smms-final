package com.smms.procurement.support;

import com.smms.entity.User;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.inventory.api.StockLedger;

import java.math.BigDecimal;
import java.util.*;

/**
 * Faithful in-memory {@link StockLedger} for unit tests: balances per (warehouse, item) with the same
 * rules as the contract (issue needs on_hand - reserved >= qty unless it consumes a reservation,
 * reserve needs availability, release needs reserved >= qty, never negative) and an append-only list
 * of the commands it applied.
 */
public class FakeStockLedger implements StockLedger {

    public record Key(UUID warehouse, UUID item) {}

    public final Map<Key, BigDecimal> onHand = new HashMap<>();
    public final Map<Key, BigDecimal> reserved = new HashMap<>();
    public final List<StockCommand> applied = new ArrayList<>();
    public final Set<UUID> knownItems = new HashSet<>();
    public final Set<UUID> knownWarehouses = new HashSet<>();

    public void seed(UUID warehouse, UUID item, String qty) {
        knownItems.add(item);
        knownWarehouses.add(warehouse);
        onHand.put(new Key(warehouse, item), new BigDecimal(qty));
    }

    public BigDecimal onHand(UUID wh, UUID item) { return onHand.getOrDefault(new Key(wh, item), BigDecimal.ZERO); }

    public BigDecimal reserved(UUID wh, UUID item) { return reserved.getOrDefault(new Key(wh, item), BigDecimal.ZERO); }

    @Override
    public WarehouseRef requireWarehouse(UUID warehouseId, User actor, Access access) {
        if (!knownWarehouses.contains(warehouseId)) throw new ResourceNotFoundException("Warehouse not found");
        return new WarehouseRef(warehouseId, "W", "W", "W");
    }

    @Override
    public ItemRef requireItem(UUID itemId, UUID centerId) {
        if (!knownItems.contains(itemId)) throw new ResourceNotFoundException("Item not found");
        return new ItemRef(itemId, "SKU", "Item", "Item", "pc");
    }

    @Override
    public List<MovementRef> apply(List<StockCommand> cmds) {
        List<MovementRef> out = new ArrayList<>();
        // all-or-nothing: work on copies, commit only when every command passes
        Map<Key, BigDecimal> oh = new HashMap<>(onHand), rs = new HashMap<>(reserved);
        for (StockCommand c : cmds) {
            Key k = new Key(c.warehouseId(), c.itemId());
            BigDecimal h = oh.getOrDefault(k, BigDecimal.ZERO), r = rs.getOrDefault(k, BigDecimal.ZERO);
            if (c.kind().isInbound()) {
                h = h.add(c.qty());
            } else {
                BigDecimal avail = c.consumeReservation() ? r : h.subtract(r);
                if (avail.compareTo(c.qty()) < 0) throw new ConflictException("Insufficient stock");
                h = h.subtract(c.qty());
                if (c.consumeReservation()) r = r.subtract(c.qty());
            }
            oh.put(k, h);
            rs.put(k, r);
            out.add(new MovementRef(UUID.randomUUID(), c.kind(), c.itemId(), c.warehouseId(), c.qty(), h));
        }
        onHand.putAll(oh);
        reserved.putAll(rs);
        applied.addAll(cmds);
        return out;
    }

    @Override
    public void reserve(UUID warehouseId, UUID itemId, BigDecimal qty) {
        Key k = new Key(warehouseId, itemId);
        if (onHand(warehouseId, itemId).subtract(reserved(warehouseId, itemId)).compareTo(qty) < 0) {
            throw new ConflictException("Insufficient stock");
        }
        reserved.merge(k, qty, BigDecimal::add);
    }

    @Override
    public void release(UUID warehouseId, UUID itemId, BigDecimal qty) {
        if (reserved(warehouseId, itemId).compareTo(qty) < 0) throw new ConflictException("Cannot release more than reserved");
        reserved.merge(new Key(warehouseId, itemId), qty.negate(), BigDecimal::add);
    }
}
