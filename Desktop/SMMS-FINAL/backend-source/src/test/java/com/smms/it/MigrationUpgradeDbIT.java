package com.smms.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Upgrade rehearsal: a scratch database (created and dropped by this test, named smms_it_upg_*) is
 * migrated to V12, filled with data of two tenants, then migrated to the latest version. V13/V14
 * must keep every row and give legacy rows neutral defaults.
 */
@Tag("db")
class MigrationUpgradeDbIT {

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
        return Flyway.configure()
            .dataSource(target.urlFor(scratchDb), target.user(), target.password())
            .locations("classpath:db/migration")
            .target(targetVersion)
            .cleanDisabled(true)
            .load();
    }

    @Test
    void v12DataSurvivesV13AndV14() {
        flyway("12").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            target.urlFor(scratchDb), target.user(), target.password()));
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class))
            .isEqualTo(12);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_name='work_orders' AND column_name='version'",
            Integer.class)).isZero();

        DbFixtures f = new DbFixtures(jdbc);
        List<UUID> workOrders = new java.util.ArrayList<>();
        UUID[] tenants = new UUID[2];
        for (int t = 0; t < 2; t++) {
            DbFixtures.Hierarchy h = f.hierarchy();
            tenants[t] = h.tenant();
            UUID requester = f.user(h, "control_room");
            f.department(h, "D" + t);
            f.asset(h);
            for (String status : List.of("new", "under_review", "assigned", "in_progress", "completed", "closed")) {
                workOrders.add(f.workOrder(h, requester, status, t == 0 ? "high" : "low"));
            }
        }
        jdbc.update("UPDATE work_orders SET current_stage = 'info_requested' WHERE id = ?", workOrders.get(1));
        long woBefore = jdbc.queryForObject("SELECT count(*) FROM work_orders", Long.class);
        long userBefore = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        assertThat(woBefore).isEqualTo(12);

        // ── upgrade ──
        flyway("14").migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_orders", Long.class)).isEqualTo(woBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(userBefore);
        assertThat(jdbc.queryForList("SELECT version::int AS v FROM flyway_schema_history WHERE success AND version::int >= 13 ORDER BY 1",
            Integer.class)).containsExactly(13, 14);

        // legacy rows get neutral defaults, no tenant or status changed
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM work_orders
             WHERE version = 0 AND sla_paused_seconds = 0 AND is_emergency = false
               AND asset_id IS NULL AND department_id IS NULL AND sla_paused_at IS NULL
               AND emergency_declared_at IS NULL AND emergency_declared_by_id IS NULL
               AND requester_confirmation IS NULL AND requester_confirmed_at IS NULL
               AND tenant_id IS NOT NULL""", Long.class)).isEqualTo(woBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_orders WHERE current_stage = 'info_requested'", Long.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE department_id IS NOT NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'chk_wo_emergency_after_created' AND convalidated",
            Long.class)).isEqualTo(1);
        for (String idx : List.of("idx_wo_asset", "idx_wo_department", "idx_wo_tenant_created", "idx_wo_tenant_completed",
                "idx_users_department", "idx_history_wo_created", "idx_idempotency_created")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Long.class, idx))
                .as(idx).isEqualTo(1);
        }

        // ── new constraints enforce ──
        UUID wo = workOrders.get(0);
        assertThatThrownBy(() -> jdbc.update("UPDATE work_orders SET requester_confirmation = 'maybe' WHERE id = ?", wo))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE work_orders SET sla_paused_seconds = -1 WHERE id = ?", wo))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE work_orders SET emergency_declared_at = created_at - interval '1 day' WHERE id = ?", wo))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("UPDATE work_orders SET requester_confirmation = 'accepted', requester_confirmed_at = now(), "
            + "is_emergency = true, emergency_declared_at = now() WHERE id = ?", wo)).isEqualTo(1);

        // idempotency table: unique (user, scope, key), tenant nullable, cascade on user delete
        UUID user = jdbc.queryForObject("SELECT requested_by_id FROM work_orders WHERE id = ?", UUID.class, wo);
        String ins = "INSERT INTO idempotency_records (id, tenant_id, user_id, scope, idem_key, fingerprint) VALUES (?, ?, ?, 's', 'k-12345678', 'f')";
        jdbc.update(ins, UUID.randomUUID(), tenants[0], user);
        assertThatThrownBy(() -> jdbc.update(ins, UUID.randomUUID(), tenants[0], user))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(ins.replace("'k-12345678'", "'k-87654321'"), UUID.randomUUID(), UUID.randomUUID(), user))
            .as("tenant FK").isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(ins.replace("'k-12345678'", "'k-platform1'"), UUID.randomUUID(), null, user);

        // migrating again is a no-op
        assertThat(flyway("14").migrate().migrationsExecuted).isZero();
    }
}
