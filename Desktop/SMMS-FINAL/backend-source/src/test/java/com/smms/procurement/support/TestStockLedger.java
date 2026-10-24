package com.smms.procurement.support;

import com.smms.entity.User;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.inventory.api.StockLedger;
import com.smms.procurement.CenterScope;
import com.smms.util.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * TEST ONLY minimal {@link StockLedger} talking to the stock tables (V30-V33 shapes), used by the DB
 * integration tests while the real {@code StockLedgerService} (W2a) is not in this branch. It is
 * registered only when no other StockLedger implementation exists in main code.
 */
@Component
@Conditional(TestStockLedger.NoRealLedger.class)
public class TestStockLedger implements StockLedger {

    static class NoRealLedger implements Condition {
        @Override
        public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
            try {
                var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
                for (var r : resolver.getResources("classpath*:com/smms/inventory/**/*.class")) {
                    String n = String.valueOf(r.getURL());
                    if (n.contains("/classes/") && !n.contains("test-classes") && n.contains("StockLedgerService")) return false;
                }
                return true;
            } catch (java.io.IOException e) {
                return true;
            }
        }
    }

    @Autowired JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WarehouseRef requireWarehouse(UUID warehouseId, User actor, Access access) {
        var rows = jdbc.query("SELECT w.id, w.code, w.name, w.name_ar, w.center_id, c.region_id, w.tenant_id "
            + "FROM warehouses w JOIN centers c ON c.id = w.center_id WHERE w.id = ? AND w.deleted_at IS NULL",
            (rs, i) -> new Object[] {new WarehouseRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4)), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class)},
            warehouseId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("Warehouse not found");
        Object[] r = rows.get(0);
        boolean roleOk = actor.getRole() != com.smms.enums.UserRole.technician
            && actor.getRole() != com.smms.enums.UserRole.control_room;
        if (!roleOk || !CenterScope.tenantMatches((UUID) r[3]) || !CenterScope.of(actor).contains((UUID) r[1], (UUID) r[2])) {
            throw new ResourceNotFoundException("Warehouse not found");
        }
        return (WarehouseRef) r[0];
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ItemRef requireItem(UUID itemId, UUID centerId) {
        UUID tenant = TenantContext.get();
        var l = jdbc.query("SELECT id, sku, name, name_ar, unit FROM inventory_items WHERE id = ? AND center_id = ? "
            + "AND deleted_at IS NULL AND (?::uuid IS NULL OR tenant_id = ?::uuid)",
            (rs, i) -> new ItemRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5)), itemId, centerId, tenant, tenant);
        if (l.isEmpty()) throw new ResourceNotFoundException("Item not found");
        return l.get(0);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MovementRef> apply(List<StockCommand> cmds) {
        List<StockCommand> sorted = new ArrayList<>(cmds);
        sorted.sort(Comparator.comparing(StockCommand::itemId).thenComparing(StockCommand::warehouseId));
        for (StockCommand c : sorted) ensureBalance(c.tenantId(), c.warehouseId(), c.itemId());
        List<MovementRef> out = new ArrayList<>();
        for (StockCommand c : sorted) {
            BigDecimal[] b = lock(c.warehouseId(), c.itemId());
            BigDecimal onHand = b[0], reserved = b[1];
            if (c.kind().isInbound()) {
                onHand = onHand.add(c.qty());
            } else {
                BigDecimal avail = c.consumeReservation() ? reserved : onHand.subtract(reserved);
                if (avail.compareTo(c.qty()) < 0 || onHand.compareTo(c.qty()) < 0) {
                    throw new ConflictException("Insufficient stock: available " + avail.toPlainString());
                }
                onHand = onHand.subtract(c.qty());
                if (c.consumeReservation()) reserved = reserved.subtract(c.qty());
            }
            jdbc.update("UPDATE stock_balances SET on_hand = ?, reserved = ?, version = version + 1 "
                + "WHERE warehouse_id = ? AND item_id = ?", onHand, reserved, c.warehouseId(), c.itemId());
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO inventory_movements (id, tenant_id, item_id, work_order_id, actor_id, type, quantity, "
                + "note, warehouse_id, movement_kind, reason_code, balance_after, reference, material_request_line_id, "
                + "po_receipt_line_id, idempotency_record_id) VALUES (?,?,?,?,?, ?::inventory_movement_type, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, c.tenantId(), c.itemId(), c.workOrderId(), c.actor() == null ? null : c.actor().getId(),
                c.kind().isInbound() ? "stock_in" : "stock_out", c.qty(), c.note(), c.warehouseId(), c.kind().name(),
                c.reasonCode(), onHand, c.reference(), c.materialRequestLineId(), c.poReceiptLineId(),
                c.idempotencyRecordId());
            out.add(new MovementRef(id, c.kind(), c.itemId(), c.warehouseId(), c.qty(), onHand));
        }
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID warehouseId, UUID itemId, BigDecimal qty) {
        BigDecimal[] b = lockExisting(warehouseId, itemId);
        if (b[0].subtract(b[1]).compareTo(qty) < 0) {
            throw new ConflictException("Insufficient stock: available " + b[0].subtract(b[1]).toPlainString());
        }
        jdbc.update("UPDATE stock_balances SET reserved = reserved + ? WHERE warehouse_id = ? AND item_id = ?",
            qty, warehouseId, itemId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID warehouseId, UUID itemId, BigDecimal qty) {
        BigDecimal[] b = lockExisting(warehouseId, itemId);
        if (b[1].compareTo(qty) < 0) throw new ConflictException("Cannot release more than reserved");
        jdbc.update("UPDATE stock_balances SET reserved = reserved - ? WHERE warehouse_id = ? AND item_id = ?",
            qty, warehouseId, itemId);
    }

    private void ensureBalance(UUID tenant, UUID wh, UUID item) {
        jdbc.update("INSERT INTO stock_balances (tenant_id, item_id, warehouse_id) VALUES (?, ?, ?) "
            + "ON CONFLICT (item_id, warehouse_id) DO NOTHING", tenant, item, wh);
    }

    private BigDecimal[] lock(UUID wh, UUID item) {
        return jdbc.queryForObject("SELECT on_hand, reserved FROM stock_balances WHERE warehouse_id = ? AND item_id = ? FOR UPDATE",
            (rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}, wh, item);
    }

    private BigDecimal[] lockExisting(UUID wh, UUID item) {
        var l = jdbc.query("SELECT on_hand, reserved FROM stock_balances WHERE warehouse_id = ? AND item_id = ? FOR UPDATE",
            (rs, i) -> new BigDecimal[] {rs.getBigDecimal(1), rs.getBigDecimal(2)}, wh, item);
        return l.isEmpty() ? new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO} : l.get(0);
    }
}
