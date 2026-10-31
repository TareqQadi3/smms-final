package com.smms.inventory;

import com.smms.it.DbFixtures;
import com.smms.it.DbItSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Upgrade rehearsal of V30-V34 on a scratch database (smms_it_upg_*, created and dropped here):
 * a V14 schema with representative legacy inventory rows is migrated to the latest version.
 */
@Tag("db")
class InventoryUpgradeDbIT {

    private static DbItSupport.Target target;
    private static String scratchDb;

    @BeforeAll
    static void create() throws Exception {
        assumeTrue(DbItSupport.configured(), DbItSupport.ENV_URL + " is not set: database tests skipped");
        target = DbItSupport.target();
        scratchDb = "smms_it_upg_inv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DbItSupport.requireThrowawayName(scratchDb);
        try (Connection c = DriverManager.getConnection(target.url(), target.user(), target.password()); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + scratchDb);
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (scratchDb == null) return;
        DbItSupport.requireThrowawayName(scratchDb);
        try (Connection c = DriverManager.getConnection(target.url(), target.user(), target.password()); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + scratchDb + " WITH (FORCE)");
        }
    }

    private Flyway flyway(String version) {
        return Flyway.configure().dataSource(target.urlFor(scratchDb), target.user(), target.password())
            .locations("classpath:db/migration").target(version).cleanDisabled(true).load();
    }

    private void item(JdbcTemplate j, UUID tenant, UUID center, UUID id, String sku, String qty) {
        j.update("INSERT INTO inventory_items (id, tenant_id, center_id, name, name_ar, sku, unit, quantity, reorder_level) VALUES (?,?,?,?,?,?,?,?::numeric,2)",
            id, tenant, center, sku, sku, sku, "pcs", qty);
    }

    @Test
    void legacyInventoryRowsSurviveV30ToV34() {
        flyway("14").migrate();
        JdbcTemplate j = new JdbcTemplate(new DriverManagerDataSource(target.urlFor(scratchDb), target.user(), target.password()));
        DbFixtures f = new DbFixtures(j);
        DbFixtures.Hierarchy a = f.hierarchy(), b = f.hierarchy();
        UUID zeroA = UUID.randomUUID(), fiveA = UUID.randomUUID(), zeroB = UUID.randomUUID(), negB = UUID.randomUUID();
        item(j, a.tenant(), a.center(), zeroA, "Z-A", "0");
        item(j, a.tenant(), a.center(), fiveA, "F-A", "5.25");
        item(j, b.tenant(), b.center(), zeroB, "Z-B", "0");
        item(j, b.tenant(), b.center(), negB, "N-B", "-3");      // unusable legacy quantity: skipped, never guessed
        UUID legacyMove = UUID.randomUUID();
        j.update("INSERT INTO inventory_movements (id, tenant_id, item_id, type, quantity, note) VALUES (?,?,?,'stock_in'::inventory_movement_type, 4.5, 'legacy')",
            legacyMove, a.tenant(), fiveA);

        flyway("34").migrate();
        assertThat(j.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class)).isZero();

        // deterministic data step: MAIN only for the center with positive stock, in its own tenant
        assertThat(j.queryForObject("SELECT count(*) FROM warehouses", Integer.class)).isEqualTo(1);
        assertThat(j.queryForObject("SELECT tenant_id FROM warehouses WHERE center_id = ? AND code = 'MAIN'", UUID.class, a.center())).isEqualTo(a.tenant());
        assertThat(j.queryForObject("SELECT on_hand FROM stock_balances WHERE item_id = ?", java.math.BigDecimal.class, fiveA)).isEqualByComparingTo("5.25");
        assertThat(j.queryForObject("SELECT count(*) FROM stock_balances", Integer.class)).isEqualTo(1);
        // opening movement matches the balance; legacy movement is untouched (no kind, widened quantity)
        assertThat(j.queryForObject("SELECT balance_after FROM inventory_movements WHERE item_id = ? AND movement_kind = 'opening'", java.math.BigDecimal.class, fiveA)).isEqualByComparingTo("5.25");
        assertThat(j.queryForObject("SELECT movement_kind IS NULL AND warehouse_id IS NULL AND quantity = 4.5 AND note = 'legacy' FROM inventory_movements WHERE id = ?", Boolean.class, legacyMove)).isTrue();
        assertThat(j.queryForObject("SELECT count(*) FROM inventory_movements", Integer.class)).isEqualTo(2);
        // tenant is now required on both tables; legacy columns kept
        assertThat(j.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name='inventory_items' AND column_name='tenant_id'", String.class)).isEqualTo("NO");
        assertThat(j.queryForObject("SELECT quantity FROM inventory_items WHERE id = ?", java.math.BigDecimal.class, fiveA)).isEqualByComparingTo("5.25");
        assertThat(j.queryForObject("SELECT is_active FROM inventory_items WHERE id = ?", Boolean.class, fiveA)).isTrue();
        // append-only trigger and negative-stock check on the migrated data
        assertThatThrownBy(() -> j.update("UPDATE inventory_movements SET note = 'x' WHERE id = ?", legacyMove)).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> j.update("DELETE FROM inventory_movements WHERE id = ?", legacyMove)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> j.update("UPDATE stock_balances SET on_hand = -1 WHERE item_id = ?", fiveA)).isInstanceOf(DataAccessException.class);
        // ledger invariant for the migrated balance
        assertThat(j.queryForObject("SELECT on_hand - COALESCE(SUM(m.quantity),0) FROM stock_balances b LEFT JOIN inventory_movements m "
            + "ON m.item_id = b.item_id AND m.warehouse_id = b.warehouse_id WHERE b.item_id = ? GROUP BY b.on_hand", java.math.BigDecimal.class, fiveA)).isEqualByComparingTo("0");
        // second run is a no-op
        assertThat(flyway("34").migrate().migrationsExecuted).isZero();
    }
}
