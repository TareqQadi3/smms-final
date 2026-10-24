package com.smms.pm;

import com.smms.it.DbFixtures;
import com.smms.it.DbItSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Upgrade rehearsal for V40-V44: a scratch database is migrated to V14 (the merged baseline, no other
 * block present), filled with legacy assets and work orders, then migrated to the latest version.
 */
@Tag("db")
class PmUpgradeDbIT {

    private static DbItSupport.Target target;
    private static String scratchDb;

    @BeforeAll
    static void create() throws Exception {
        assumeTrue(DbItSupport.configured(), DbItSupport.ENV_URL + " is not set: database tests skipped");
        target = DbItSupport.target();
        scratchDb = "smms_it_upg_pm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DbItSupport.requireThrowawayName(scratchDb);
        try (Connection c = DriverManager.getConnection(target.url(), target.user(), target.password());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + scratchDb);
        }
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

    private Flyway flyway(String version) {
        var cfg = Flyway.configure().dataSource(target.urlFor(scratchDb), target.user(), target.password())
            .locations("classpath:db/migration").cleanDisabled(true);
        if (version != null) cfg.target(version);
        return cfg.load();
    }

    @Test
    void v14DataSurvivesV40ToV44() {
        flyway("14").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(target.urlFor(scratchDb), target.user(), target.password()));
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(14);

        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h1 = f.hierarchy();
        DbFixtures.Hierarchy h2 = f.hierarchy();
        UUID a1 = f.asset(h1), a2 = f.asset(h1), a3 = f.asset(h2);
        UUID requester = f.user(h1, "control_room");
        UUID wo = f.workOrder(h1, requester, "in_progress", "high");
        jdbc.update("UPDATE work_orders SET asset_id = ? WHERE id = ?", a1, wo);
        jdbc.update("UPDATE assets SET status = 'broken' WHERE id = ?", a2);

        flyway(null).migrate();

        assertThat(jdbc.queryForList("SELECT version::int FROM flyway_schema_history WHERE success AND version::int >= 40 ORDER BY 1", Integer.class))
            .containsExactly(40, 41, 42, 43, 44);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assets", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status::text FROM assets WHERE id = ?", String.class, a2)).isEqualTo("broken");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assets WHERE asset_code IS NULL AND criticality IS NULL "
            + "AND decommissioned_at IS NULL AND version = 0 AND tenant_id IS NOT NULL", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT asset_id FROM work_orders WHERE id = ?", UUID.class, wo)).isEqualTo(a1);
        for (String t : new String[] {"maintenance_plans", "maintenance_plan_runs", "asset_downtime"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t, Long.class)).as(t).isZero();
        }
        // legacy rows can use the new features right away
        jdbc.update("UPDATE assets SET asset_code = 'A-1' WHERE id = ?", a1);
        jdbc.update("UPDATE assets SET asset_code = 'A-1' WHERE id = ?", a3); // other center and tenant: allowed
        assertThat(flyway(null).migrate().migrationsExecuted).isZero(); // second run is a no-op
    }
}
