package com.smms.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Upgrade from V14 (scratch database smms_it_upg_*, created and dropped here): data of two tenants is
 * created at V14, then V20-V25 run. Backfills must be exact and never guess (V20 legacy open info
 * request, V21 legacy breach rows, V24 department links), keep every row, and a second migrate is a no-op.
 */
@Tag("db")
class WorkflowUpgradeDbIT {

    private static DbItSupport.Target target;
    private static String scratchDb;

    @BeforeAll
    static void createScratchDatabase() throws Exception {
        assumeTrue(DbItSupport.configured(), DbItSupport.ENV_URL + " is not set: database tests skipped");
        target = DbItSupport.target();
        scratchDb = "smms_it_upg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        DbItSupport.requireThrowawayName(scratchDb);
        try (Connection c = DriverManager.getConnection(target.url(), target.user(), target.password());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + scratchDb);
        }
    }

    @AfterAll
    static void dropScratchDatabase() throws Exception {
        if (scratchDb == null) return;
        DbItSupport.requireThrowawayName(scratchDb);
        try (Connection c = DriverManager.getConnection(target.url(), target.user(), target.password());
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + scratchDb + " WITH (FORCE)");
        }
    }

    private Flyway flyway(String targetVersion) {
        return Flyway.configure().dataSource(target.urlFor(scratchDb), target.user(), target.password())
            .locations("classpath:db/migration").target(targetVersion).cleanDisabled(true).load();
    }

    private UUID deptUser(JdbcTemplate jdbc, DbFixtures.Hierarchy h, String departmentName) {
        UUID id = UUID.randomUUID();
        String n = "dep-" + id.toString().substring(0, 8);
        jdbc.update("INSERT INTO users (id, tenant_id, region_id, center_id, username, email, password_hash, full_name, role, is_active, department_name) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'x', ?, 'department'::user_role, true, ?)",
            id, h.tenant(), h.region(), h.center(), n, n + "@t.invalid", n, departmentName);
        return id;
    }

    private UUID departmentNamed(JdbcTemplate jdbc, DbFixtures.Hierarchy h, String name, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, tenant_id, center_id, name, name_ar, code) VALUES (?, ?, ?, ?, ?, ?)",
            id, h.tenant(), h.center(), name, name, code);
        return id;
    }

    @Test
    void v14DataUpgradesToV25WithExactBackfills() {
        flyway("14").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(target.urlFor(scratchDb), target.user(), target.password()));
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy();
        DbFixtures.Hierarchy b = f.hierarchy();

        // departments: exact (case/space insensitive) match, ambiguous name, unmatched, other tenant's namesake
        UUID radiology = departmentNamed(jdbc, a, "Radiology", "RAD");
        departmentNamed(jdbc, a, "Lab", "LAB1");
        departmentNamed(jdbc, a, "lab", "LAB2");
        departmentNamed(jdbc, b, "Pharmacy", "PH");
        UUID uExact = deptUser(jdbc, a, "  radiology ");
        UUID uAmbiguous = deptUser(jdbc, a, "Lab");
        UUID uUnmatched = deptUser(jdbc, a, "Cardiology");
        UUID uForeignName = deptUser(jdbc, a, "Pharmacy"); // exists only in tenant b
        UUID uNoName = deptUser(jdbc, a, null);

        // tickets: by the matched user (department role), by the ambiguous one, by a control room user
        UUID woExact = f.workOrder(a, uExact, "new", "medium");
        UUID woAmbiguous = f.workOrder(a, uAmbiguous, "new", "medium");
        jdbc.update("UPDATE work_orders SET created_by_role = 'department'::user_role WHERE id IN (?, ?)", woExact, woAmbiguous);
        UUID cr = f.user(a, "control_room");
        UUID woCr = f.workOrder(a, cr, "new", "medium");

        // V20: one ticket waiting for information with its opening history row, one without any history
        UUID woInfo = f.workOrder(a, uExact, "under_review", "medium");
        UUID woInfoNoHistory = f.workOrder(b, f.user(b, "department"), "under_review", "medium");
        jdbc.update("UPDATE work_orders SET current_stage = 'info_requested' WHERE id IN (?, ?)", woInfo, woInfoNoHistory);
        jdbc.update("INSERT INTO ticket_history (work_order_id, actor_id, actor_role, from_status, to_status, stage, note, tenant_id) "
            + "VALUES (?, ?, 'control_room', 'under_review', 'under_review', 'info_requested', 'Information requested: which room?', ?)",
            woInfo, cr, a.tenant());
        Object pausedBefore = jdbc.queryForObject("SELECT sla_deadline FROM work_orders WHERE id = ?", Object.class, woInfo);

        // V21: breached tickets
        UUID woBreached = f.workOrder(a, cr, "in_progress", "high");
        UUID woNotBreached = f.workOrder(a, cr, "in_progress", "high");
        jdbc.update("UPDATE work_orders SET sla_breached = true WHERE id = ?", woBreached);
        long wosBefore = jdbc.queryForObject("SELECT count(*) FROM work_orders", Long.class);
        long usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);

        // ── upgrade ──
        flyway("25").migrate();
        assertThat(jdbc.queryForList("SELECT version::int FROM flyway_schema_history WHERE success AND version::int >= 20 ORDER BY 1", Integer.class))
            .containsExactly(20, 21, 22, 23, 24, 25);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_orders", Long.class)).isEqualTo(wosBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(usersBefore);

        // V24: only the exact, unique match is linked; nothing else changed
        assertThat(deptOf(jdbc, uExact)).isEqualTo(radiology);
        for (UUID u : List.of(uAmbiguous, uUnmatched, uForeignName, uNoName, cr)) assertThat(deptOf(jdbc, u)).as(u.toString()).isNull();
        assertThat(jdbc.queryForObject("SELECT department_id FROM work_orders WHERE id = ?", UUID.class, woExact)).isEqualTo(radiology);
        assertThat(jdbc.queryForObject("SELECT department_id FROM work_orders WHERE id = ?", UUID.class, woAmbiguous)).isNull();
        assertThat(jdbc.queryForObject("SELECT department_id FROM work_orders WHERE id = ?", UUID.class, woCr)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM departments", Long.class)).isEqualTo(4); // never inserts departments

        // V20: the verified open request is recorded from history; the SLA is NOT paused retroactively
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_info_requests", Long.class)).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT work_order_id, requested_by_id, question, answered_at, tenant_id FROM work_order_info_requests");
        assertThat(row.get("work_order_id")).isEqualTo(woInfo);
        assertThat(row.get("requested_by_id")).isEqualTo(cr);
        assertThat(row.get("question")).isEqualTo("which room?");
        assertThat(row.get("answered_at")).isNull();
        assertThat(row.get("tenant_id")).isEqualTo(a.tenant());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_orders WHERE sla_paused_at IS NOT NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT sla_deadline FROM work_orders WHERE id = ?", Object.class, woInfo)).isEqualTo(pausedBefore);

        // V21: legacy level-1 rows exactly for breached tickets with a tenant
        assertThat(jdbc.queryForList("SELECT work_order_id FROM work_order_escalations WHERE level = 1 AND trigger_kind = 'legacy_breach'", UUID.class))
            .containsExactly(woBreached);
        assertThat(jdbc.queryForObject("SELECT notified_count IS NULL FROM work_order_escalations WHERE work_order_id = ?", Boolean.class, woBreached)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_escalations WHERE work_order_id = ?", Long.class, woNotBreached)).isZero();

        // new tables have no NULL tenant and the new tables start empty
        for (String t : List.of("work_order_info_requests", "work_order_escalations", "work_order_ratings", "work_order_status_links")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t + " WHERE tenant_id IS NULL", Long.class)).as(t).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_ratings", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_order_status_links", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'attachments' AND column_name = 'photo_phase'", Long.class)).isEqualTo(1);

        // tenant diagnostics (V10) stay clean for the existing tables
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_orders WHERE tenant_id IS NULL", Long.class)).isZero();

        // second run changes nothing
        assertThat(flyway("25").migrate().migrationsExecuted).isZero();
    }

    private static UUID deptOf(JdbcTemplate jdbc, UUID user) {
        return jdbc.queryForObject("SELECT department_id FROM users WHERE id = ?", UUID.class, user);
    }
}
