package com.smms.reports.inventory;

import com.smms.entity.Center;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.procurement.CenterScope;
import com.smms.procurement.DateRange;
import com.smms.repository.CenterRepository;
import com.smms.reports.csv.CsvWriter;
import com.smms.util.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Inventory and purchasing reports (read-only SQL). Scope: tenant always, then system_admin = tenant,
 * region_supervisor = region, other roles = own center. Date ranges are validated ({@link DateRange}),
 * results are capped at {@link CsvWriter#DEFAULT_MAX_ROWS} rows (422 "narrow the range").
 * The stock tables (warehouses, stock_balances, ledger columns of inventory_movements) belong to the
 * inventory block; this class only reads them.
 */
@Service
@RequiredArgsConstructor
public class InventoryReportService {

    public static final UserRole[] INVENTORY_ROLES = {UserRole.system_admin, UserRole.region_supervisor,
        UserRole.hospital_supervisor, UserRole.maintenance_manager, UserRole.electrical_manager,
        UserRole.operating_company_manager, UserRole.warehouse};
    public static final UserRole[] PURCHASING_ROLES = {UserRole.system_admin, UserRole.region_supervisor,
        UserRole.hospital_supervisor, UserRole.maintenance_manager, UserRole.electrical_manager,
        UserRole.operating_company_manager};
    private static final int CAP = CsvWriter.DEFAULT_MAX_ROWS;

    /** A tabular report: column names and rows (typed cells). */
    public record Report(String name, LocalDate from, LocalDate to, List<String> columns, List<List<Object>> rows) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final CenterRepository centerRepo;

    @Transactional(readOnly = true)
    public Report stock(User actor, UUID warehouseId, UUID centerId) {
        CenterScope.requireRole(actor, INVENTORY_ROLES);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = scope(actor, centerId, p, "w.tenant_id");
        if (warehouseId != null) { w.append(" AND w.id = :wh"); p.addValue("wh", warehouseId); }
        String sql = "SELECT c.code AS center, w.code AS warehouse, i.sku, i.name AS item, COALESCE(u.code, i.unit) AS uom, "
            + "b.on_hand, b.reserved, b.on_hand - b.reserved AS available, b.min_qty, "
            + "(b.min_qty IS NOT NULL AND b.on_hand <= b.min_qty) AS below_min "
            + "FROM stock_balances b JOIN inventory_items i ON i.id = b.item_id "
            + "JOIN warehouses w ON w.id = b.warehouse_id JOIN centers c ON c.id = w.center_id "
            + "LEFT JOIN units_of_measure u ON u.id = i.uom_id WHERE 1=1" + w
            + " ORDER BY c.code, w.code, i.sku LIMIT " + (CAP + 1);
        return run("stock", null, null, p, sql);
    }

    @Transactional(readOnly = true)
    public Report movements(User actor, LocalDate from, LocalDate to, UUID warehouseId, UUID itemId, String kind,
                            UUID centerId) {
        CenterScope.requireRole(actor, INVENTORY_ROLES);
        DateRange range = DateRange.orDefault(from, to);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = scope(actor, centerId, p, "i.tenant_id");
        range(range, p, w, "m.created_at");
        if (warehouseId != null) { w.append(" AND m.warehouse_id = :wh"); p.addValue("wh", warehouseId); }
        if (itemId != null) { w.append(" AND m.item_id = :item"); p.addValue("item", itemId); }
        if (kind != null && !kind.isBlank()) {
            w.append(" AND m.movement_kind = :kind");
            p.addValue("kind", kind.toLowerCase(Locale.ROOT));
        }
        String sql = "SELECT m.created_at, c.code AS center, wh.code AS warehouse, m.movement_kind AS kind, i.sku, "
            + "i.name AS item, m.quantity, m.balance_after, wo.ticket_number, m.reference, m.reason_code, "
            + "u.username AS actor FROM inventory_movements m JOIN inventory_items i ON i.id = m.item_id "
            + "JOIN centers c ON c.id = i.center_id LEFT JOIN warehouses wh ON wh.id = m.warehouse_id "
            + "LEFT JOIN work_orders wo ON wo.id = m.work_order_id LEFT JOIN users u ON u.id = m.actor_id "
            + "WHERE 1=1" + w + " ORDER BY m.created_at DESC, m.id LIMIT " + (CAP + 1);
        return run("movements", range.from(), range.to(), p, sql);
    }

    /** Net consumption (issues minus returns) grouped by work order, item or asset. */
    @Transactional(readOnly = true)
    public Report consumption(User actor, LocalDate from, LocalDate to, String groupBy, UUID centerId) {
        CenterScope.requireRole(actor, INVENTORY_ROLES);
        DateRange range = DateRange.orDefault(from, to);
        String group = groupBy == null ? "workOrder" : groupBy;
        String cols;
        String grp;
        switch (group) {
            case "workOrder" -> { cols = "wo.ticket_number AS work_order, wo.title"; grp = "wo.ticket_number, wo.title"; }
            case "item" -> { cols = "i.sku, i.name AS item"; grp = "i.sku, i.name"; }
            case "asset" -> { cols = "COALESCE(a.name, '(no asset)') AS asset"; grp = "COALESCE(a.name, '(no asset)')"; }
            default -> throw new IllegalArgumentException("groupBy must be workOrder, item or asset");
        }
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = scope(actor, centerId, p, "i.tenant_id");
        range(range, p, w, "m.created_at");
        String sql = "SELECT " + cols + ", "
            + "SUM(CASE WHEN m.movement_kind = 'issue' THEN m.quantity ELSE 0 END) AS issued, "
            + "SUM(CASE WHEN m.movement_kind = 'return_in' THEN m.quantity ELSE 0 END) AS returned, "
            + "SUM(CASE WHEN m.movement_kind = 'issue' THEN m.quantity ELSE -m.quantity END) AS net "
            + "FROM inventory_movements m JOIN inventory_items i ON i.id = m.item_id "
            + "JOIN centers c ON c.id = i.center_id JOIN work_orders wo ON wo.id = m.work_order_id "
            + "LEFT JOIN assets a ON a.id = wo.asset_id "
            + "WHERE m.movement_kind IN ('issue', 'return_in')" + w
            + " GROUP BY " + grp + " ORDER BY " + grp + " LIMIT " + (CAP + 1);
        return run("consumption-" + group, range.from(), range.to(), p, sql);
    }

    @Transactional(readOnly = true)
    public Report purchaseOrders(User actor, LocalDate from, LocalDate to, String status, UUID centerId) {
        CenterScope.requireRole(actor, PURCHASING_ROLES);
        DateRange range = DateRange.orDefault(from, to);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = scope(actor, centerId, p, "po.tenant_id");
        range(range, p, w, "po.created_at");
        if (status != null && !status.isBlank()) {
            w.append(" AND po.status = :status");
            p.addValue("status", status.toLowerCase(Locale.ROOT));
        }
        String sql = "SELECT po.po_number, po.status, c.code AS center, s.name AS supplier, po.created_at, "
            + "po.expected_date, (SELECT count(*) FROM purchase_order_lines l WHERE l.po_id = po.id) AS lines, "
            + "(SELECT COALESCE(sum(l.qty_ordered), 0) FROM purchase_order_lines l WHERE l.po_id = po.id) AS qty_ordered, "
            + "(SELECT COALESCE(sum(l.qty_received), 0) FROM purchase_order_lines l WHERE l.po_id = po.id) AS qty_received, "
            + "(SELECT COALESCE(sum(l.qty_ordered * l.unit_price), 0) FROM purchase_order_lines l WHERE l.po_id = po.id) AS total "
            + "FROM purchase_orders po JOIN centers c ON c.id = po.center_id JOIN suppliers s ON s.id = po.supplier_id "
            + "WHERE 1=1" + w + " ORDER BY po.created_at DESC, po.id LIMIT " + (CAP + 1);
        return run("purchase-orders", range.from(), range.to(), p, sql);
    }

    @Transactional(readOnly = true)
    public Report openMaterialRequests(User actor, UUID centerId) {
        CenterScope.requireRole(actor, INVENTORY_ROLES);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = scope(actor, centerId, p, "mr.tenant_id");
        String sql = "SELECT mr.request_number, mr.status, c.code AS center, wo.ticket_number, rb.username AS requested_by, "
            + "mr.created_at, floor(extract(epoch FROM (now() - mr.created_at)) / 86400)::int AS age_days, "
            + "(SELECT COALESCE(sum(l.qty_requested), 0) FROM material_request_lines l WHERE l.mr_id = mr.id) AS qty_requested, "
            + "(SELECT COALESCE(sum(l.qty_issued), 0) FROM material_request_lines l WHERE l.mr_id = mr.id) AS qty_issued "
            + "FROM material_requests mr JOIN centers c ON c.id = mr.center_id JOIN work_orders wo ON wo.id = mr.work_order_id "
            + "JOIN users rb ON rb.id = mr.requested_by_id "
            + "WHERE mr.status IN ('submitted', 'approved', 'reserved', 'issued')" + w
            + " ORDER BY mr.created_at, mr.id LIMIT " + (CAP + 1);
        return run("open-material-requests", null, null, p, sql);
    }

    // ── helpers ──────────────────────────────────────────────

    /** Tenant + location predicates (alias {@code c} is the center table). Returns " AND ..." text. */
    StringBuilder scope(User actor, UUID centerFilter, MapSqlParameterSource p, String tenantColumn) {
        StringBuilder w = new StringBuilder();
        CenterScope scope = CenterScope.of(actor);
        UUID tenant = TenantContext.get();
        if (tenant != null) { w.append(" AND ").append(tenantColumn).append(" = :tenant"); p.addValue("tenant", tenant); }
        switch (scope.kind()) {
            case ALL -> { }
            case REGION -> { w.append(" AND c.region_id = :region"); p.addValue("region", scope.regionId()); }
            case CENTER -> { w.append(" AND c.id = :scopeCenter"); p.addValue("scopeCenter", scope.centerId()); }
            case NONE -> w.append(" AND 1 = 0");
        }
        if (centerFilter != null) {
            Center c = centerRepo.findById(centerFilter).filter(x -> x.getDeletedAt() == null).orElse(null);
            if (c == null || !CenterScope.tenantMatches(c.getTenantId()) || !scope.contains(c)) {
                throw new ResourceNotFoundException("Center not found");
            }
            w.append(" AND c.id = :filterCenter");
            p.addValue("filterCenter", centerFilter);
        }
        return w;
    }

    private static void range(DateRange r, MapSqlParameterSource p, StringBuilder w, String column) {
        w.append(" AND ").append(column).append(" >= :from AND ").append(column).append(" < :to");
        p.addValue("from", Timestamp.from(r.start()));
        p.addValue("to", Timestamp.from(r.endExclusive()));
    }

    private Report run(String name, LocalDate from, LocalDate to, MapSqlParameterSource p, String sql) {
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = jdbc.query(sql, p, rs -> {
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
            List<List<Object>> out = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(columns.size());
                for (int i = 1; i <= columns.size(); i++) row.add(cell(rs.getObject(i)));
                out.add(row);
            }
            return out;
        });
        if (rows.size() > CAP) throw new WorkflowException("Report has more than " + CAP + " rows; narrow the range");
        return new Report(name, from, to, columns, rows);
    }

    static Object cell(Object v) {
        if (v instanceof Timestamp t) return t.toInstant();
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        return v;
    }
}
