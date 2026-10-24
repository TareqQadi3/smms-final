package com.smms.procurement.service;

import com.smms.entity.Center;
import com.smms.entity.User;
import com.smms.inventory.api.StockLedger.ItemRef;
import com.smms.inventory.api.StockLedger.WarehouseRef;
import com.smms.procurement.dto.Refs.CenterRef;
import com.smms.procurement.dto.Refs.UserRef;
import com.smms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only lookups of inventory master data for DISPLAY and tenant/center consistency checks.
 * The inventory tables belong to the inventory block (V30/V31/V32): this class only reads them
 * (warehouses, units_of_measure, stock_balances), so documents stay viewable by roles that have no
 * warehouse permission (technician, control room) and after an item is deactivated.
 */
@Component
@RequiredArgsConstructor
public class RefLookup {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepo;

    public record WarehouseInfo(UUID id, UUID tenantId, UUID centerId, String code, String name, String nameAr,
                                boolean active) {
        public WarehouseRef ref() { return new WarehouseRef(id, code, name, nameAr); }
    }

    public Optional<WarehouseInfo> warehouse(UUID id) {
        if (id == null) return Optional.empty();
        List<WarehouseInfo> l = jdbc.query(
            "SELECT id, tenant_id, center_id, code, name, name_ar, is_active FROM warehouses "
                + "WHERE id = ? AND deleted_at IS NULL",
            (rs, i) -> new WarehouseInfo(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7)),
            id);
        return l.stream().findFirst();
    }

    public Map<UUID, ItemRef> items(Collection<UUID> ids) {
        Map<UUID, ItemRef> out = new HashMap<>();
        for (UUID id : ids) {
            jdbc.query("SELECT i.id, i.sku, i.name, i.name_ar, COALESCE(u.code, i.unit) "
                    + "FROM inventory_items i LEFT JOIN units_of_measure u ON u.id = i.uom_id WHERE i.id = ?",
                (rs, n) -> new ItemRef(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)), id).forEach(r -> out.put(r.id(), r));
        }
        return out;
    }

    /** on_hand - reserved of one balance; zero when there is no balance row. */
    public BigDecimal available(UUID warehouseId, UUID itemId) {
        List<BigDecimal> l = jdbc.query(
            "SELECT on_hand - reserved FROM stock_balances WHERE warehouse_id = ? AND item_id = ?",
            (rs, i) -> rs.getBigDecimal(1), warehouseId, itemId);
        return l.isEmpty() ? BigDecimal.ZERO : l.get(0);
    }

    public Map<UUID, UserRef> users(Collection<UUID> ids) {
        Map<UUID, UserRef> out = new HashMap<>();
        for (User u : userRepo.findAllById(ids)) out.put(u.getId(), userRef(u));
        return out;
    }

    public static UserRef userRef(User u) {
        return u == null ? null : new UserRef(u.getId(), u.getFullName(), u.getFullNameAr());
    }

    public static CenterRef centerRef(Center c) {
        return c == null ? null : new CenterRef(c.getId(), c.getName(), c.getNameAr(), c.getCode());
    }
}
