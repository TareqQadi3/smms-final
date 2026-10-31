package com.smms.procurement;

import com.smms.it.DbFixtures;
import com.smms.it.DbItSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * V35-V39 on a database upgraded from V14 (legacy rows kept) and constraint behaviour of the new
 * tables. Runs only with -Pit against a throwaway server; uses and drops its own smms_it_upg_* database.
 */
@Tag("db")
class ProcurementMigrationDbIT {

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
        FluentConfiguration c = Flyway.configure()
            .dataSource(target.urlFor(scratchDb), target.user(), target.password())
            .cleanDisabled(true);
        c.locations("classpath:db/migration");
        if (targetVersion != null) c.target(targetVersion);
        return c.load();
    }

    @Test
    void upgradeFromV14KeepsLegacyRowsAndAddsTheProcurementTables() {
        flyway("14").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            target.urlFor(scratchDb), target.user(), target.password()));
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID requester = f.user(h, "control_room");
        UUID wo = f.workOrder(h, requester, "in_progress", "medium");
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_items (id, tenant_id, center_id, name, name_ar, sku, quantity) VALUES (?,?,?,'i','i','S1',5)",
            item, h.tenant(), h.center());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'suppliers'", Long.class)).isZero();

        flyway(null).migrate();

        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_items WHERE id = ?", java.math.BigDecimal.class, item)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_orders WHERE id = ?", Long.class, wo)).isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT version::int FROM flyway_schema_history WHERE success AND version::int BETWEEN 35 AND 39 ORDER BY 1",
            Integer.class)).containsExactly(35, 36, 37, 38, 39);
        for (String t : List.of("suppliers", "purchase_orders", "purchase_order_lines", "po_receipts", "po_receipt_lines",
                "material_requests", "material_request_lines")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t, Long.class)).as(t).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = 'tenant_id' AND is_nullable = 'NO'",
                Long.class, t)).as(t + " tenant_id NOT NULL").isEqualTo(1L);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname IN ('fk_im_mr_line','fk_im_po_receipt_line','fk_po_material_request') AND convalidated",
            Long.class)).isEqualTo(3L);
        // second migrate is a no-op
        assertThat(flyway(null).migrate().migrationsExecuted).isZero();

        // numbering triggers
        UUID supplier = UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, tenant_id, code, name) VALUES (?,?,'S','Sup')", supplier, h.tenant());
        UUID wh = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses (id, tenant_id, center_id, code, name, name_ar) VALUES (?,?,?,'M','Main','Main')", wh, h.tenant(), h.center());
        UUID creator = f.user(h, "warehouse"), approver = f.user(h, "maintenance_manager");
        UUID po = UUID.randomUUID();
        jdbc.update("INSERT INTO purchase_orders (id, tenant_id, po_number, center_id, supplier_id, warehouse_id, created_by_id) "
            + "VALUES (?,?,'x',?,?,?,?)", po, h.tenant(), h.center(), supplier, wh, creator);
        assertThat(jdbc.queryForObject("SELECT po_number FROM purchase_orders WHERE id = ?", String.class, po)).matches("PO-\\d{4}-\\d{5}");
        UUID mr = UUID.randomUUID();
        jdbc.update("INSERT INTO material_requests (id, tenant_id, request_number, center_id, work_order_id, warehouse_id, requested_by_id) "
            + "VALUES (?,?,'x',?,?,?,?)", mr, h.tenant(), h.center(), wo, wh, requester);
        assertThat(jdbc.queryForObject("SELECT request_number FROM material_requests WHERE id = ?", String.class, mr)).matches("MR-\\d{4}-\\d{5}");

        // constraints
        assertThatThrownBy(() -> jdbc.update("UPDATE purchase_orders SET approved_by_id = ? WHERE id = ?", creator, po))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE purchase_orders SET approved_by_id = ?, status = 'approved' WHERE id = ?", approver, po);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO purchase_order_lines (tenant_id, po_id, line_no, item_id, qty_ordered, qty_received) "
            + "VALUES (?,?,1,?,5,6)", h.tenant(), po, item)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO purchase_order_lines (tenant_id, po_id, line_no, item_id, qty_ordered) VALUES (?,?,1,?,5)", h.tenant(), po, item);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO purchase_order_lines (tenant_id, po_id, line_no, item_id, qty_ordered) VALUES (?,?,2,?,1)",
            h.tenant(), po, item)).isInstanceOf(DataIntegrityViolationException.class); // one line per item
        // a line of another tenant under this PO is refused by the composite FK
        DbFixtures.Hierarchy other = f.hierarchy();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO purchase_order_lines (tenant_id, po_id, line_no, item_id, qty_ordered) VALUES (?,?,3,?,1)",
            other.tenant(), po, item)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO material_request_lines (tenant_id, mr_id, item_id, qty_requested, qty_approved) VALUES (?,?,?,2,3)",
            h.tenant(), mr, item)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO suppliers (tenant_id, code, name) VALUES (?,'S','dup')", h.tenant()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
