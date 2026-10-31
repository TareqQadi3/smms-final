package com.smms.it;

import com.smms.reports.ReportModels.*;
import com.smms.reports.ReportQueries;
import com.smms.reports.ReportRange;
import com.smms.reports.ReportScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * W4 migrations V50/V51 on a scratch database (created and dropped here): upgrade from V14 with
 * existing notification data, the second migrate is a no-op, constraints enforce. The same database
 * checks the escalation report both before the escalation table exists and after it is created
 * with the planned shape (the table arrives with another workstream).
 */
@Tag("db")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class W4MigrationDbIT {

    private static DbItSupport.Target target;
    private static String scratchDb;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void create() throws Exception {
        assumeTrue(DbItSupport.configured(), DbItSupport.ENV_URL + " is not set: database tests skipped");
        target = DbItSupport.target();
        scratchDb = "smms_it_upg_w4" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DbItSupport.requireThrowawayName(scratchDb);
        try (Connection c = DriverManager.getConnection(target.url(), target.user(), target.password());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + scratchDb);
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(target.urlFor(scratchDb), target.user(), target.password()));
    }

    @AfterAll
    static void drop() throws Exception {
        if (scratchDb == null) return;
        DbItSupport.requireThrowawayName(scratchDb);
        try (Connection c = DriverManager.getConnection(target.url(), target.user(), target.password());
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + scratchDb + " WITH (FORCE)");
        }
    }

    private Flyway flyway(String targetVersion) {

        var cfg = Flyway.configure().dataSource(target.urlFor(scratchDb), target.user(), target.password())
            .locations("classpath:db/migration").cleanDisabled(true);
        if (targetVersion != null) cfg.target(targetVersion);
        return cfg.load();
    }

    @Test
    @Order(1)
    void v14DataSurvivesV50AndV51() {
        flyway("14").migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name IN ('notification_preferences','notification_deliveries')", Long.class)).isZero();
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID user = f.user(h, "control_room");
        UUID n = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id, user_id, type, title, tenant_id) VALUES (?, ?, 'NEW_TICKET', 'x', ?)", n, user, h.tenant());

        var result = flyway(null).migrate();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForList("SELECT version::int FROM flyway_schema_history WHERE success AND version::int >= 50 ORDER BY 1", Integer.class))
            .contains(50, 51);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE id = ?", Long.class, n)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_preferences", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_deliveries", Long.class)).isZero();
        assertThat(flyway(null).migrate().migrationsExecuted).as("second run is a no-op").isZero();

        // preferences: defaults off, size check, tenant FK, updated_at trigger
        jdbc.update("INSERT INTO notification_preferences (user_id, tenant_id) VALUES (?, ?)", user, h.tenant());
        assertThat(jdbc.queryForObject("SELECT email_enabled OR sms_enabled FROM notification_preferences WHERE user_id = ?", Boolean.class, user)).isFalse();
        assertThatThrownBy(() -> jdbc.update("UPDATE notification_preferences SET types = '{}' WHERE user_id = ?", user))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notification_preferences (user_id, tenant_id) VALUES (?, ?)", f.user(h, "technician"), UUID.randomUUID()))
            .isInstanceOf(DataIntegrityViolationException.class);
        // deliveries: defaults and checks
        jdbc.update("INSERT INTO notification_deliveries (tenant_id, notification_id, user_id, channel) VALUES (?, ?, ?, 'sms')", h.tenant(), n, user);
        assertThat(jdbc.queryForMap("SELECT status, attempts FROM notification_deliveries WHERE notification_id = ?", n))
            .containsEntry("status", "pending").containsEntry("attempts", 0);
        assertThatThrownBy(() -> jdbc.update("UPDATE notification_deliveries SET attempts = -1 WHERE notification_id = ?", n))
            .isInstanceOf(DataIntegrityViolationException.class);
        for (String status : List.of("pending", "failed", "dead", "not_configured", "skipped_no_address")) {
            assertThat(jdbc.update("UPDATE notification_deliveries SET status = ? WHERE notification_id = ?", status, n)).isEqualTo(1);
        }
    }

    @Test
    @Order(2)
    void escalationReportReadsTheRealTableAndToleratesItsAbsence() {
        ReportQueries q = new ReportQueries(new NamedParameterJdbcTemplate(jdbc));
        ReportRange range = new ReportRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy(), b = f.hierarchy();
        UUID reqA = f.user(a, "control_room"), reqB = f.user(b, "control_room");
        UUID x = f.workOrder(a, reqA, "in_progress", "high"), y = f.workOrder(a, reqA, "in_progress", "high");
        UUID z = f.workOrder(b, reqB, "in_progress", "high");
        ReportScope tenantA = new ReportScope(a.tenant(), null, null);

        // V21 (workflow program) is part of the migration chain now: the report reads the real table
        assertThat(q.escalationsAvailable()).isTrue();
        // tolerance: while the table is absent the report is empty and flagged unavailable
        jdbc.execute("ALTER TABLE work_order_escalations RENAME TO work_order_escalations_hidden");
        try {
            assertThat(q.escalationsAvailable()).isFalse();
            assertThat(q.escalations(range, tenantA)).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE work_order_escalations_hidden RENAME TO work_order_escalations");
        }

        String ins = "INSERT INTO work_order_escalations (tenant_id, work_order_id, level, trigger_kind, created_at) VALUES (?, ?, ?, ?, ?::timestamptz)";
        jdbc.update(ins, a.tenant(), x, 1, "sla_breach", "2026-03-05T10:00:00Z");
        jdbc.update(ins, a.tenant(), x, 2, "sla_overdue", "2026-03-06T10:00:00Z");
        jdbc.update(ins, a.tenant(), y, 1, "manual", "2026-03-05T11:00:00Z");
        jdbc.update(ins, b.tenant(), z, 1, "sla_breach", "2026-03-05T11:00:00Z");   // other tenant
        jdbc.update("INSERT INTO work_order_escalations (tenant_id, work_order_id, level, trigger_kind, created_at) VALUES (?, ?, 3, 'emergency', '2026-04-02T00:00:00Z')", a.tenant(), y); // outside range

        assertThat(q.escalationsAvailable()).isTrue();
        EscalationReport e = q.escalations(range, tenantA).orElseThrow();
        assertThat(e.available()).isTrue();
        assertThat(e.groups()).containsExactly(new EscalationGroup(1, "manual", 1), new EscalationGroup(1, "sla_breach", 1),
            new EscalationGroup(2, "sla_overdue", 1));
        assertThat(e.byDay()).containsExactly(new DayCount("2026-03-05", 2), new DayCount("2026-03-06", 1));
        assertThat(q.escalations(range, new ReportScope(a.tenant(), a.center(), null)).orElseThrow().groups()).hasSize(3);
        assertThat(q.escalations(range, new ReportScope(a.tenant(), UUID.randomUUID(), null)).orElseThrow().groups()).isEmpty();
        assertThat(q.escalations(range, new ReportScope(b.tenant(), null, null)).orElseThrow().groups())
            .containsExactly(new EscalationGroup(1, "sla_breach", 1));
        assertThat(Instant.now()).isNotNull();
    }
}
