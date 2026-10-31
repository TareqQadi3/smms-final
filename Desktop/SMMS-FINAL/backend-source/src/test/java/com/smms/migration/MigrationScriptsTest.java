package com.smms.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V1/V2 under db/migration are copies of scripts/init.sql and scripts/V2__schema_migration.sql
 * (the Docker build context cannot reach ../scripts). They must never drift: Flyway checksums
 * them, and the entrypoint runs the originals.
 */
class MigrationScriptsTest {

    private static final Path SCRIPTS = Path.of("..", "scripts");

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = MigrationScriptsTest.class.getResourceAsStream("/db/migration/" + name)) {
            assertThat(in).as("classpath db/migration/" + name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void baselineCopiesAreByteIdenticalToScripts() throws Exception {
        Path init = SCRIPTS.resolve("init.sql");
        Path v2 = SCRIPTS.resolve("V2__schema_migration.sql");
        assumeTrue(Files.exists(init) && Files.exists(v2), "../scripts not available (e.g. Docker build)");
        assertThat(sha256(resource("V1__init.sql"))).isEqualTo(sha256(Files.readAllBytes(init)));
        assertThat(sha256(resource("V2__schema_migration.sql"))).isEqualTo(sha256(Files.readAllBytes(v2)));
    }

    @Test
    void versionsAreContiguousAndPresent() {
        for (String f : List.of("V1__init.sql", "V2__schema_migration.sql", "V3__user_role_other.sql",
                "V4__tenant_backfill_parents.sql", "V5__child_tenant_backfill.sql",
                "V6__child_tenant_constraints.sql", "V7__default_tenant_seed_hierarchy.sql",
                "V8__username_case_insensitive_unique.sql",
                "V9__rederive_tenant_after_seed.sql", "V10__tenant_diagnostics_views.sql")) {
            assertThat(MigrationScriptsTest.class.getResource("/db/migration/" + f)).as(f).isNotNull();
        }
    }

    @Test
    void tenantBackfillNeverUsesADefaultTenant() throws Exception {
        for (String f : List.of("V4__tenant_backfill_parents.sql", "V5__child_tenant_backfill.sql")) {
            String sql = new String(resource(f)).toLowerCase();
            assertThat(sql).as(f).doesNotContain("default_tenant").doesNotContain("limit 1");
            // every tenant_id assignment must come from a parent join (or be the system_admin clear)
            assertThat(sql).as(f).doesNotContain("set tenant_id = '");
        }
    }

    @Test
    void migrationsAreTransactionalAndNeverDestructive() throws Exception {
        for (String f : List.of("V3__user_role_other.sql", "V4__tenant_backfill_parents.sql",
                "V5__child_tenant_backfill.sql", "V6__child_tenant_constraints.sql",
                "V7__default_tenant_seed_hierarchy.sql", "V8__username_case_insensitive_unique.sql",
                "V9__rederive_tenant_after_seed.sql", "V10__tenant_diagnostics_views.sql")) {
            String sql = new String(resource(f)).toLowerCase();
            assertThat(sql).as(f).doesNotContain("drop table").doesNotContain("truncate")
                .doesNotContain("delete from").doesNotContain("concurrently");
        }
    }

    @Test
    void seedTenantMigrationOnlyTouchesFixedSeedIdsWhereTenantIsNull() throws Exception {
        String sql = new String(resource("V7__default_tenant_seed_hierarchy.sql")).toLowerCase();
        assertThat(sql).contains("11111111-1111-1111-1111-111111111111")
            .contains("22222222-2222-2222-2222-222222222222");
        for (String stmt : sql.split(";")) {
            if (stmt.contains("update ")) {
                assertThat(stmt).contains("tenant_id is null");
                assertThat(stmt.contains("'11111111-1111") || stmt.contains("'22222222-2222")).isTrue();
            }
        }
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)--.*$", "");
    }

    @Test
    void v9OnlyFillsNullTenantsFromParentsAndNeverUsesADefaultTenant() throws Exception {
        String sql = stripComments(new String(resource("V9__rederive_tenant_after_seed.sql"))).toLowerCase();
        assertThat(sql).doesNotContain("default").doesNotContain("limit 1").doesNotContain("set tenant_id = '")
            .doesNotContain("is distinct from").doesNotContain("insert into").doesNotContain("drop ")
            .doesNotContain("delete from").doesNotContain("truncate");
        int updates = 0;
        for (String stmt : sql.split(";")) {
            // the notifications update carries its subquery; the outer statement guards on n.tenant_id
            if (stmt.trim().startsWith("update ")) {
                updates++;
                assertThat(stmt).as(stmt).contains("tenant_id is null");
                // never assigns NULL or a literal; the value always comes from a joined parent
                assertThat(stmt).doesNotContain("tenant_id = null");
            }
        }
        assertThat(updates).isEqualTo(12);
    }

    @Test
    void v10IsReadOnlyAndHasNoSelectStar() throws Exception {
        String sql = stripComments(new String(resource("V10__tenant_diagnostics_views.sql"))).toLowerCase();
        assertThat(sql).doesNotContain("update ").doesNotContain("insert ").doesNotContain("delete from")
            .doesNotContain("alter ").doesNotContain("drop ").doesNotContain("truncate")
            .doesNotContain("select *").doesNotContain(".*");
        assertThat(sql).contains("create or replace view diag_tenant_mismatch")
            .contains("create or replace view diag_tenant_null").contains("function diag_tenant_summary")
            .contains("stable");
    }

    @Test
    void v60IsReadOnlyAndKeepsEveryV10Check() throws Exception {
        String sql = stripComments(new String(resource("V60__tenant_diagnostics_fp1.sql"))).toLowerCase();
        assertThat(sql).doesNotContain("update ").doesNotContain("insert ").doesNotContain("delete from")
            .doesNotContain("alter ").doesNotContain("drop ").doesNotContain("truncate")
            .doesNotContain("select *").doesNotContain(".*");
        assertThat(sql).contains("create or replace view diag_tenant_mismatch")
            .contains("create or replace view diag_tenant_null").contains("function diag_tenant_summary").contains("stable");
        String v10 = new String(resource("V10__tenant_diagnostics_views.sql"));
        String v60 = new String(resource("V60__tenant_diagnostics_fp1.sql"));
        var m = java.util.regex.Pattern.compile("SELECT '([a-z_]+)'::text").matcher(v10.substring(v10.indexOf("diag_tenant_mismatch AS"), v10.indexOf("diag_tenant_null AS")));
        while (m.find()) assertThat(v60).as(m.group(1)).contains("'" + m.group(1) + "'::text");
        // the new tables are covered
        for (String t : java.util.List.of("maintenance_plans", "maintenance_plan_runs", "asset_downtime", "warehouses",
                "units_of_measure", "stock_balances", "notification_preferences", "notification_deliveries",
                "work_order_info_requests", "work_order_escalations", "work_order_ratings", "work_order_status_links",
                "suppliers", "purchase_orders", "purchase_order_lines", "po_receipts", "po_receipt_lines",
                "material_requests", "material_request_lines")) {
            assertThat(v60).as(t).contains("FROM " + t + " WHERE tenant_id IS NULL");
        }
        assertThat(v60).contains("idempotency_records_user");
    }

    @Test
    void v60CheckNamesMatchTheServiceWhitelist() throws Exception {
        String sql = new String(resource("V60__tenant_diagnostics_fp1.sql"));
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        var m = java.util.regex.Pattern.compile("SELECT '([a-z_]+)'::text( AS check_name)?").matcher(sql);
        while (m.find()) {
            // the first literal of each select in diag_tenant_mismatch is the check name; the null view
            // uses "AS table_name" instead and is excluded by the section split
            names.add(m.group(1));
        }
        String mismatchView = sql.substring(sql.indexOf("diag_tenant_mismatch AS"), sql.indexOf("diag_tenant_null AS"));
        java.util.Set<String> inMismatch = new java.util.LinkedHashSet<>();
        var m2 = java.util.regex.Pattern.compile("SELECT '([a-z_]+)'::text").matcher(mismatchView);
        while (m2.find()) inMismatch.add(m2.group(1));
        assertThat(inMismatch).containsExactlyInAnyOrderElementsOf(
            com.smms.diagnostics.TenantDiagnosticsService.CHECKS);
        assertThat(com.smms.diagnostics.TenantDiagnosticsService.CHECKS).doesNotHaveDuplicates();
    }

    @Test
    void migrationVersionsHaveNoGapsUpToTen() throws Exception {
        for (int v = 1; v <= 10; v++) {
            final int version = v;
            try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
                assertThat(files.filter(p -> p.getFileName().toString().startsWith("V" + version + "__")).count())
                    .as("V" + version).isEqualTo(1);
            }
        }
    }
}
