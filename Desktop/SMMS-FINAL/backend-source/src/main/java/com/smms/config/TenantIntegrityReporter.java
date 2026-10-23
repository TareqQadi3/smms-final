package com.smms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.smms.diagnostics.TenantDiagnosticsService;

import java.util.List;
import java.util.Map;

/**
 * Read-only startup report of rows without a tenant. It only runs SELECTs, never writes and never
 * fails startup. Schema/data fixes are Flyway migrations; users without a tenant are fixed by a
 * platform admin through POST /api/admin/users/{id}/tenant. Only ids are logged (max 50), no
 * names or emails.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class TenantIntegrityReporter implements ApplicationRunner {

    static final int MAX_IDS = 50;

    /** Fixed table names (never user input). Users are handled separately. */
    static final List<String> TABLES = List.of(
        "regions", "centers", "control_rooms", "work_orders", "maintenance_types", "departments",
        "assets", "inventory_items", "inventory_movements", "ticket_history", "attachments",
        "notifications");

    static final String USERS_WHERE =
        "FROM users WHERE tenant_id IS NULL AND deleted_at IS NULL AND role::text <> 'system_admin'";

    private final JdbcTemplate jdbc;
    private final TenantDiagnosticsService diagnostics;

    @Value("${smms.startup.integrity-report.enabled:true}")
    private boolean enabled = true;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        try {
            report();
        } catch (RuntimeException e) {
            log.warn("Tenant integrity report skipped: {}", e.getClass().getSimpleName());
        }
    }

    void report() {
        long total = 0;
        for (String table : TABLES) {
            Long n = count("SELECT count(*) FROM " + table + " WHERE tenant_id IS NULL", table);
            if (n != null && n > 0) {
                total += n;
                log.warn("Tenant integrity: {} rows in '{}' have no tenant (not modified)", n, table);
            }
        }
        Long users = count("SELECT count(*) " + USERS_WHERE, "users");
        if (users != null && users > 0) {
            total += users;
            List<String> ids = jdbc.queryForList(
                "SELECT id::text " + USERS_WHERE + " ORDER BY id LIMIT " + MAX_IDS, String.class);
            log.warn("Tenant integrity: {} non-admin users have no tenant and cannot sign in; "
                + "a platform admin must assign one (POST /api/admin/users/{id}/tenant). ids(max {})={}",
                users, MAX_IDS, ids);
        }
        if (total == 0) log.info("Tenant integrity: no rows without a tenant");
        reportMismatches();
        reportFlyway();
    }

    /** Wrong-tenant rows (both tenants set and different, or parent tenant-less). Ids only, never fixed. */
    void reportMismatches() {
        try {
            long total = 0;
            for (Map.Entry<String, Long> e : diagnostics.mismatchCounts().entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                total += e.getValue();
                List<String> ids = diagnostics.mismatchIds(e.getKey(), MAX_IDS);
                log.warn("Tenant integrity: check '{}' has {} inconsistent rows (not modified, owner review "
                    + "required). ids(max {})={}", e.getKey(), e.getValue(), MAX_IDS, ids);
            }
            if (total == 0) log.info("Tenant integrity: no wrong-tenant rows detected");
        } catch (RuntimeException ex) {
            log.debug("Tenant integrity: mismatch checks skipped ({})", ex.getClass().getSimpleName());
        }
    }

    void reportFlyway() {
        try {
            TenantDiagnosticsService.FlywayState st = diagnostics.flywayState();
            if (st.failedMigrations() > 0) {
                log.error("Flyway: {} migration(s) recorded with success=false in flyway_schema_history; "
                    + "see the migration runbook, do not repair by hand", st.failedMigrations());
            }
            log.info("Flyway: current version {}", st.currentVersion());
            for (String d : st.deferredItems()) {
                log.warn("Flyway deferred item: {}", d);
            }
        } catch (RuntimeException ex) {
            log.debug("Tenant integrity: flyway state skipped ({})", ex.getClass().getSimpleName());
        }
    }

    private Long count(String sql, String label) {
        try {
            return jdbc.queryForObject(sql, Long.class);
        } catch (RuntimeException e) {
            // table or column not present yet; a report must never break startup
            log.debug("Tenant integrity: '{}' not checked ({})", label, e.getClass().getSimpleName());
            return null;
        }
    }
}
