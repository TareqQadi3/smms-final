package com.smms.diagnostics;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only tenant diagnostics over the V10 views (extended by V60 to the feature-program-1 tables). Fixed SQL only (check names are whitelisted and
 * bound as parameters), ids only in every result, never any write. Wrong-tenant rows are reported
 * for the owner to review; this class cannot and must not fix them.
 */
@Service
@RequiredArgsConstructor
public class TenantDiagnosticsService {

    /** Must equal the check_name literals of view diag_tenant_mismatch as last defined by V60 (asserted by a unit test). */
    public static final List<String> CHECKS = List.of(
        "centers_region",
        "control_rooms_center",
        "users_center",
        "users_region",
        "work_orders_center",
        "work_orders_control_room",
        "work_orders_requested_by",
        "work_orders_assigned_to",
        "departments_center",
        "assets_center",
        "assets_department",
        "inventory_items_center",
        "inventory_movements_item",
        "inventory_movements_work_order",
        "ticket_history_work_order",
        "attachments_work_order",
        "notifications_user",
        "notifications_work_order",
        "centers_region_parent_tenantless",
        "control_rooms_center_parent_tenantless",
        "users_center_parent_tenantless",
        "users_region_parent_tenantless",
        "work_orders_center_parent_tenantless",
        "work_orders_control_room_parent_tenantless",
        "work_orders_requested_by_parent_tenantless",
        "work_orders_assigned_to_parent_tenantless",
        "departments_center_parent_tenantless",
        "assets_center_parent_tenantless",
        "assets_department_parent_tenantless",
        "inventory_items_center_parent_tenantless",
        "inventory_movements_item_parent_tenantless",
        "inventory_movements_work_order_parent_tenantless",
        "ticket_history_work_order_parent_tenantless",
        "attachments_work_order_parent_tenantless",
        "notifications_user_parent_tenantless",
        "notifications_work_order_parent_tenantless",
        "work_order_info_requests_work_order",
        "work_order_info_requests_requested_by",
        "work_order_info_requests_answered_by",
        "work_order_escalations_work_order",
        "work_order_escalations_triggered_by",
        "work_order_ratings_work_order",
        "work_order_ratings_rated_by",
        "work_order_status_links_work_order",
        "work_order_status_links_created_by",
        "warehouses_center",
        "inventory_items_uom",
        "stock_balances_item",
        "stock_balances_warehouse",
        "inventory_movements_warehouse",
        "purchase_orders_center",
        "purchase_orders_supplier",
        "purchase_orders_warehouse",
        "purchase_orders_work_order",
        "purchase_orders_created_by",
        "purchase_orders_approved_by",
        "purchase_orders_material_request",
        "purchase_order_lines_po",
        "purchase_order_lines_item",
        "po_receipts_po",
        "po_receipts_warehouse",
        "po_receipts_received_by",
        "po_receipt_lines_receipt",
        "po_receipt_lines_po_line",
        "material_requests_center",
        "material_requests_work_order",
        "material_requests_warehouse",
        "material_requests_requested_by",
        "material_requests_decided_by",
        "material_request_lines_mr",
        "material_request_lines_item",
        "maintenance_plans_center",
        "maintenance_plans_asset",
        "maintenance_plans_control_room",
        "maintenance_plans_owner",
        "maintenance_plan_runs_plan",
        "maintenance_plan_runs_work_order",
        "asset_downtime_asset",
        "asset_downtime_work_order",
        "asset_downtime_recorded_by",
        "asset_downtime_ended_by",
        "notification_preferences_user",
        "notification_deliveries_notification",
        "notification_deliveries_user",
        "work_order_info_requests_work_order_parent_tenantless",
        "work_order_info_requests_requested_by_parent_tenantless",
        "work_order_info_requests_answered_by_parent_tenantless",
        "work_order_escalations_work_order_parent_tenantless",
        "work_order_escalations_triggered_by_parent_tenantless",
        "work_order_ratings_work_order_parent_tenantless",
        "work_order_ratings_rated_by_parent_tenantless",
        "work_order_status_links_work_order_parent_tenantless",
        "work_order_status_links_created_by_parent_tenantless",
        "warehouses_center_parent_tenantless",
        "inventory_items_uom_parent_tenantless",
        "stock_balances_item_parent_tenantless",
        "stock_balances_warehouse_parent_tenantless",
        "inventory_movements_warehouse_parent_tenantless",
        "purchase_orders_center_parent_tenantless",
        "purchase_orders_supplier_parent_tenantless",
        "purchase_orders_warehouse_parent_tenantless",
        "purchase_orders_work_order_parent_tenantless",
        "purchase_orders_created_by_parent_tenantless",
        "purchase_orders_approved_by_parent_tenantless",
        "purchase_orders_material_request_parent_tenantless",
        "purchase_order_lines_po_parent_tenantless",
        "purchase_order_lines_item_parent_tenantless",
        "po_receipts_po_parent_tenantless",
        "po_receipts_warehouse_parent_tenantless",
        "po_receipts_received_by_parent_tenantless",
        "po_receipt_lines_receipt_parent_tenantless",
        "po_receipt_lines_po_line_parent_tenantless",
        "material_requests_center_parent_tenantless",
        "material_requests_work_order_parent_tenantless",
        "material_requests_warehouse_parent_tenantless",
        "material_requests_requested_by_parent_tenantless",
        "material_requests_decided_by_parent_tenantless",
        "material_request_lines_mr_parent_tenantless",
        "material_request_lines_item_parent_tenantless",
        "maintenance_plans_center_parent_tenantless",
        "maintenance_plans_asset_parent_tenantless",
        "maintenance_plans_control_room_parent_tenantless",
        "maintenance_plans_owner_parent_tenantless",
        "maintenance_plan_runs_plan_parent_tenantless",
        "maintenance_plan_runs_work_order_parent_tenantless",
        "asset_downtime_asset_parent_tenantless",
        "asset_downtime_work_order_parent_tenantless",
        "asset_downtime_recorded_by_parent_tenantless",
        "asset_downtime_ended_by_parent_tenantless",
        "notification_preferences_user_parent_tenantless",
        "notification_deliveries_notification_parent_tenantless",
        "notification_deliveries_user_parent_tenantless",
        "idempotency_records_user",
        "work_orders_center_vs_room_center",
        "user_room_access_user_vs_room",
        "user_room_access_room_tenantless",
        "system_admin_with_tenant");

    public static final Set<String> CHECK_SET = Set.copyOf(CHECKS);

    /** Constraints V6/V9 may leave NOT VALID on legacy data. */
    static final List<String> DEFERRABLE_CONSTRAINTS = List.of(
        "fk_ticket_history_tenant", "fk_attachments_tenant", "fk_notifications_tenant",
        "attachments_file_size_nonneg", "work_orders_closed_after_created",
        "work_orders_completed_after_created", "work_orders_sla_after_created",
        "notifications_tenant_when_work_order");

    static final String SQL_MISMATCH_COUNTS =
        "SELECT check_name, n FROM diag_tenant_summary() WHERE check_name NOT LIKE 'null:%'";
    static final String SQL_NULL_COUNTS =
        "SELECT substr(check_name, 6) AS t, n FROM diag_tenant_summary() WHERE check_name LIKE 'null:%'";
    static final String SQL_MISMATCH_IDS =
        "SELECT row_id::text FROM diag_tenant_mismatch WHERE check_name = ? ORDER BY row_id LIMIT ?";
    static final String SQL_MISMATCH_PAGE =
        "SELECT check_name, table_name, row_id::text, row_tenant::text, expected_tenant::text, ref_table, "
        + "ref_id::text FROM diag_tenant_mismatch WHERE check_name = ? ORDER BY check_name, row_id "
        + "LIMIT ? OFFSET ?";
    static final String SQL_MISMATCH_TOTAL =
        "SELECT count(*) FROM diag_tenant_mismatch WHERE check_name = ?";
    static final String SQL_FLYWAY_CURRENT =
        "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL "
        + "ORDER BY installed_rank DESC LIMIT 1";
    static final String SQL_FLYWAY_FAILED =
        "SELECT count(*) FROM flyway_schema_history WHERE NOT success";
    static final String SQL_UNVALIDATED =
        "SELECT conname FROM pg_constraint WHERE NOT convalidated AND conname = ANY (?::text[]) "
        + "ORDER BY conname";
    static final String SQL_MISSING_CONSTRAINTS =
        "SELECT n FROM unnest(?::text[]) AS n WHERE NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = n) "
        + "ORDER BY n";
    static final String SQL_NOT_NULL_MISSING =
        "SELECT c.relname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
        + "JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public' "
        + "WHERE c.relname IN ('ticket_history', 'attachments') AND a.attname = 'tenant_id' "
        + "AND NOT a.attnotnull ORDER BY c.relname";
    static final String SQL_USERNAME_INDEX =
        "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'uq_users_username_lower'";

    private final JdbcTemplate jdbc;

    public record FlywayState(String currentVersion, long failedMigrations, List<String> deferredItems) {}

    public record Summary(Map<String, Long> mismatchCounts, Map<String, Long> nullTenantCounts,
                          long mismatchTotal, FlywayState flyway) {}

    public record MismatchRow(String check, String table, String rowId, String rowTenantId,
                              String expectedTenantId, String refTable, String refId) {}

    public record MismatchPage(String check, int page, int size, long total, List<MismatchRow> content) {}

    /** Every check with its count (zeros included), in whitelist order. */
    @Transactional(readOnly = true)
    public Map<String, Long> mismatchCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String c : CHECKS) out.put(c, 0L);
        jdbc.query(SQL_MISMATCH_COUNTS, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> nullTenantCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(SQL_NULL_COUNTS, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }

    @Transactional(readOnly = true)
    public List<String> mismatchIds(String check, int limit) {
        requireKnownCheck(check);
        return jdbc.queryForList(SQL_MISMATCH_IDS, String.class, check, limit);
    }

    @Transactional(readOnly = true)
    public MismatchPage mismatchPage(String check, int page, int size) {
        requireKnownCheck(check);
        List<MismatchRow> rows = jdbc.query(SQL_MISMATCH_PAGE,
            (rs, i) -> new MismatchRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7)),
            check, size, (long) page * size);
        Long total = jdbc.queryForObject(SQL_MISMATCH_TOTAL, Long.class, check);
        return new MismatchPage(check, page, size, total == null ? 0 : total, rows);
    }

    @Transactional(readOnly = true)
    public FlywayState flywayState() {
        String current = null;
        long failed = 0;
        try {
            List<String> v = jdbc.queryForList(SQL_FLYWAY_CURRENT, String.class);
            current = v.isEmpty() ? null : v.get(0);
            Long f = jdbc.queryForObject(SQL_FLYWAY_FAILED, Long.class);
            failed = f == null ? 0 : f;
        } catch (RuntimeException e) {
            // no flyway_schema_history (not managed by Flyway): reported as unknown
        }
        return new FlywayState(current, failed, deferredItems());
    }

    /** Items V6/V8/V9 deliberately left undone because legacy data violated them. */
    public List<String> deferredItems() {
        List<String> items = new ArrayList<>();
        try {
            for (String c : jdbc.queryForList(SQL_UNVALIDATED, String.class,
                    "{" + String.join(",", DEFERRABLE_CONSTRAINTS) + "}")) {
                items.add("constraint " + c + " is NOT VALID");
            }
            for (String c : jdbc.queryForList(SQL_MISSING_CONSTRAINTS, String.class,
                    "{" + String.join(",", DEFERRABLE_CONSTRAINTS) + "}")) {
                items.add("constraint " + c + " is not present (deferred)");
            }
            for (String t : jdbc.queryForList(SQL_NOT_NULL_MISSING, String.class)) {
                items.add(t + ".tenant_id is nullable (NOT NULL deferred)");
            }
            Long idx = jdbc.queryForObject(SQL_USERNAME_INDEX, Long.class);
            if (idx == null || idx == 0) items.add("index uq_users_username_lower is missing");
        } catch (RuntimeException e) {
            items.add("deferred items could not be read");
        }
        return items;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Map<String, Long> m = mismatchCounts();
        long total = m.values().stream().mapToLong(Long::longValue).sum();
        return new Summary(m, nullTenantCounts(), total, flywayState());
    }

    private static void requireKnownCheck(String check) {
        if (check == null || !CHECK_SET.contains(check)) {
            throw new IllegalArgumentException("Unknown check");
        }
    }
}
