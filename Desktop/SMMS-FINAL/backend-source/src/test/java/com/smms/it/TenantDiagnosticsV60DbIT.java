package com.smms.it;

import com.smms.diagnostics.TenantDiagnosticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V60 views: every whitelisted check runs, new tables are covered, and a wrong-tenant row is reported (read-only). */
class TenantDiagnosticsV60DbIT extends AbstractDbIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantDiagnosticsService diagnostics;

    @Test
    void everyWhitelistedCheckExistsInTheViewAndSummaryRuns() {
        List<String> inView = jdbc.queryForList("SELECT DISTINCT check_name FROM diag_tenant_summary() WHERE check_name NOT LIKE 'null:%'", String.class);
        // groups with zero rows are absent from the summary; each check must at least be a valid filter value
        for (String c : TenantDiagnosticsService.CHECKS) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM diag_tenant_mismatch WHERE check_name = ?", Long.class, c)).as(c).isNotNull();
        }
        assertThat(TenantDiagnosticsService.CHECKS).contains("maintenance_plans_asset", "asset_downtime_asset", "warehouses_center",
            "stock_balances_warehouse", "purchase_orders_supplier", "material_request_lines_mr", "notification_deliveries_notification",
            "work_order_ratings_work_order", "idempotency_records_user");
        assertThat(inView).isNotNull();
        assertThat(diagnostics.mismatchCounts().keySet()).containsExactlyElementsOf(TenantDiagnosticsService.CHECKS);
    }

    @Test
    void wrongTenantRowsAreReportedNotChanged() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy(), b = f.hierarchy();
        UUID wh = UUID.randomUUID();
        // warehouse of tenant A placed in a center of tenant B (plain FK: the database accepts it)
        jdbc.update("INSERT INTO warehouses (id, tenant_id, center_id, code, name, name_ar) VALUES (?,?,?,'X','X','X')", wh, a.tenant(), b.center());
        UUID owner = f.user(a, "technician");
        UUID idem = UUID.randomUUID();
        UUID otherTenantUser = f.user(b, "technician");
        jdbc.update("INSERT INTO idempotency_records (id, tenant_id, user_id, scope, idem_key, fingerprint) VALUES (?,?,?,'t','k1','f')", idem, a.tenant(), otherTenantUser);
        try {
        Map<String, Long> counts = diagnostics.mismatchCounts();
        assertThat(counts.get("warehouses_center")).isGreaterThanOrEqualTo(1L);
        assertThat(counts.get("idempotency_records_user")).isGreaterThanOrEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT row_id::text FROM diag_tenant_mismatch WHERE check_name = 'warehouses_center'", String.class)).contains(wh.toString());
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM warehouses WHERE id = ?", UUID.class, wh)).isEqualTo(a.tenant());
        assertThat(owner).isNotNull();
        // a consistent row is not reported
        UUID ok = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses (id, tenant_id, center_id, code, name, name_ar) VALUES (?,?,?,'Y','Y','Y')", ok, b.tenant(), b.center());
        assertThat(jdbc.queryForList("SELECT row_id::text FROM diag_tenant_mismatch WHERE table_name = 'warehouses'", String.class)).doesNotContain(ok.toString());
        } finally { // keep the shared throwaway database free of deliberate mismatches for other tests
            jdbc.update("DELETE FROM idempotency_records WHERE id = ?", idem);
            jdbc.update("DELETE FROM warehouses WHERE id = ?", wh);
        }
    }
}
