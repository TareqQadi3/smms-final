package com.smms.inventory;

import com.smms.entity.User;
import com.smms.exception.ConflictException;
import com.smms.exception.ResourceNotFoundException;
import com.smms.exception.WorkflowException;
import com.smms.idempotency.IdempotencyService.Outcome;
import com.smms.inventory.api.InsufficientStockException;
import com.smms.inventory.api.StockLedger;
import com.smms.inventory.api.StockLedger.MovementKind;
import com.smms.inventory.api.StockLedger.StockCommand;
import com.smms.inventory.dto.Dtos.*;
import com.smms.inventory.service.*;
import com.smms.it.AbstractDbIT;
import com.smms.it.DbFixtures;
import com.smms.repository.UserRepository;
import com.smms.util.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stock ledger against a real PostgreSQL: concurrency, idempotency, reversals, append-only trigger, CHECK backstop, isolation. */
class StockLedgerDbIT extends AbstractDbIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired StockLedgerService ledger;
    @Autowired StockOperationsService operations;
    @Autowired ItemService itemService;
    @Autowired CatalogAdminService catalog;
    @Autowired StockQueryService queries;
    @Autowired UserRepository users;

    @AfterEach void clearTenant() { TenantContext.clear(); }

    record Ctx(DbFixtures.Hierarchy h, UUID warehouse, User mm, User wh, User te) {}

    private TransactionTemplate tx() { return new TransactionTemplate(txManager); }

    private Ctx ctx() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID wh = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses (id, tenant_id, center_id, code, name, name_ar) VALUES (?,?,?,?,?,?)",
            wh, h.tenant(), h.center(), "W" + f.tag, "WH", "WH");
        return new Ctx(h, wh, users.findById(f.user(h, "maintenance_manager")).orElseThrow(),
            users.findById(f.user(h, "warehouse")).orElseThrow(), users.findById(f.user(h, "technician")).orElseThrow());
    }

    private UUID item(Ctx c, String sku) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO inventory_items (id, tenant_id, center_id, name, name_ar, sku) VALUES (?,?,?,?,?,?)",
            id, c.h.tenant(), c.h.center(), sku, sku, sku + "-" + id.toString().substring(0, 6));
        return id;
    }

    private StockCommand cmd(Ctx c, UUID item, MovementKind k, String qty) {
        boolean corr = k.name().startsWith("adjust");
        return new StockCommand(c.h.tenant(), c.warehouse, item, k, new BigDecimal(qty), false, null, null, null, null,
            corr ? "other" : null, corr ? "test correction" : null, null, c.mm, null);
    }

    private void receive(Ctx c, UUID item, String qty) {
        tx().executeWithoutResult(s -> ledger.apply(List.of(cmd(c, item, MovementKind.receipt, qty))));
    }

    private BigDecimal onHand(UUID item, UUID wh) {
        return jdbc.queryForObject("SELECT on_hand FROM stock_balances WHERE item_id=? AND warehouse_id=?", BigDecimal.class, item, wh);
    }

    private void assertInvariant(UUID item, UUID wh) {
        BigDecimal sum = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN movement_kind IN ('opening','receipt','return_in','adjustment_in','reversal_in') "
            + "THEN quantity ELSE -quantity END),0) FROM inventory_movements WHERE item_id=? AND warehouse_id=?", BigDecimal.class, item, wh);
        assertThat(onHand(item, wh)).isEqualByComparingTo(sum);
        // balance_after chains in insert order
        BigDecimal run = BigDecimal.ZERO;
        for (Map<String, Object> r : jdbc.queryForList("SELECT movement_kind k, quantity q, balance_after b FROM inventory_movements "
            + "WHERE item_id=? AND warehouse_id=? ORDER BY seq", item, wh)) {
            boolean in = StockLedger.MovementKind.valueOf((String) r.get("k")).isInbound();
            run = in ? run.add((BigDecimal) r.get("q")) : run.subtract((BigDecimal) r.get("q"));
            assertThat((BigDecimal) r.get("b")).isEqualByComparingTo(run);
        }
    }

    @Test
    void parallelIssuesNeverDriveStockNegative() throws Exception {
        Ctx c = ctx();
        UUID item = item(c, "I1");
        receive(c, item, "10");
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(), insufficient = new AtomicInteger(), other = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                try {
                    tx().executeWithoutResult(s -> ledger.apply(List.of(cmd(c, item, MovementKind.issue, "1"))));
                    ok.incrementAndGet();
                } catch (InsufficientStockException e) {
                    assertThat(e.getMessage()).contains("available");
                    insufficient.incrementAndGet();
                } catch (Exception e) { other.incrementAndGet(); }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(ok.get()).isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(10);
        assertThat(other.get()).isZero();
        assertThat(onHand(item, c.warehouse)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE item_id=? AND movement_kind='issue'", Integer.class, item)).isEqualTo(10);
        assertInvariant(item, c.warehouse);
    }

    @Test
    void sameIdempotencyKeyConcurrentlyReceivesOnce() throws Exception {
        Ctx c = ctx();
        UUID item = item(c, "I2");
        ReceiptRequest req = new ReceiptRequest(c.warehouse, "ref", "note", List.of(new ReceiptLine(item, new BigDecimal("5"))));
        String key = "key-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome<ReceiptResponse>>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) fs.add(pool.submit(() -> { go.await(); return operations.receipt(c.mm, key, req); }));
        go.countDown();
        int replays = 0, firsts = 0;
        for (Future<Outcome<ReceiptResponse>> f : fs) {
            Outcome<ReceiptResponse> o = f.get(60, TimeUnit.SECONDS);
            if (o.replayed()) replays++; else firsts++;
            assertThat(o.body().movements()).hasSize(1);
        }
        pool.shutdown();
        assertThat(firsts).isEqualTo(1);
        assertThat(replays).isEqualTo(9);
        assertThat(onHand(item, c.warehouse)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE item_id=?", Integer.class, item)).isEqualTo(1);
        // same key, different request: 422
        assertThatThrownBy(() -> operations.receipt(c.mm, key,
            new ReceiptRequest(c.warehouse, "ref", "note", List.of(new ReceiptLine(item, new BigDecimal("6"))))))
            .isInstanceOf(WorkflowException.class);
        // missing or malformed key: 400
        assertThatThrownBy(() -> operations.receipt(c.mm, null, req)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operations.receipt(c.mm, "short", req)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reversalIsExactAuditableAndOnlyOnce() {
        Ctx c = ctx();
        UUID item = item(c, "I3");
        Outcome<ReceiptResponse> r = operations.receipt(c.wh, "rcpt-" + UUID.randomUUID(),
            new ReceiptRequest(c.warehouse, null, null, List.of(new ReceiptLine(item, new BigDecimal("7.5")))));
        UUID receiptMove = r.body().movements().get(0).id();
        // warehouse keeper may not reverse or adjust
        assertThatThrownBy(() -> operations.reverse(c.wh, "rev-" + UUID.randomUUID(), receiptMove, new ReverseRequest("wrong delivery")))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> operations.adjust(c.wh, "adj-" + UUID.randomUUID(),
            new AdjustmentRequest(c.warehouse, item, "out", BigDecimal.ONE, "damage", "broken box")))
            .isInstanceOf(AccessDeniedException.class);

        Outcome<MovementResponse> rev = operations.reverse(c.mm, "rev-" + UUID.randomUUID(), receiptMove, new ReverseRequest("wrong delivery"));
        assertThat(rev.body().kind()).isEqualTo(MovementKind.reversal_out);
        assertThat(rev.body().quantity()).isEqualByComparingTo("7.5");
        assertThat(rev.body().balanceAfter()).isEqualByComparingTo("0");
        assertThat(rev.body().reversalOfId()).isEqualTo(receiptMove);
        assertThat(onHand(item, c.warehouse)).isEqualByComparingTo("0");
        // once only, and a reversal cannot be reversed
        assertThatThrownBy(() -> operations.reverse(c.mm, "rev-" + UUID.randomUUID(), receiptMove, new ReverseRequest("again please")))
            .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> operations.reverse(c.mm, "rev-" + UUID.randomUUID(), rev.body().id(), new ReverseRequest("undo the undo")))
            .isInstanceOf(WorkflowException.class);
        // a reversal needing stock that is gone: receipt 3, issue 3, reversing the receipt fails with 409
        UUID item2 = item(c, "I3b");
        Outcome<ReceiptResponse> r2 = operations.receipt(c.mm, "rcpt-" + UUID.randomUUID(),
            new ReceiptRequest(c.warehouse, null, null, List.of(new ReceiptLine(item2, new BigDecimal("3")))));
        tx().executeWithoutResult(s -> ledger.apply(List.of(cmd(c, item2, MovementKind.issue, "3"))));
        assertThatThrownBy(() -> operations.reverse(c.mm, "rev-" + UUID.randomUUID(), r2.body().movements().get(0).id(), new ReverseRequest("too late now")))
            .isInstanceOf(InsufficientStockException.class);
        // reversing the issue puts the stock back
        UUID issueId = jdbc.queryForObject("SELECT id FROM inventory_movements WHERE item_id=? AND movement_kind='issue'", UUID.class, item2);
        Outcome<MovementResponse> back = operations.reverse(c.mm, "rev-" + UUID.randomUUID(), issueId, new ReverseRequest("issued by mistake"));
        assertThat(back.body().kind()).isEqualTo(MovementKind.reversal_in);
        assertThat(onHand(item2, c.warehouse)).isEqualByComparingTo("3");
        assertInvariant(item, c.warehouse);
        assertInvariant(item2, c.warehouse);
    }

    @Test
    void concurrentReversalsOfOneMovementProduceOne() throws Exception {
        Ctx c = ctx();
        UUID item = item(c, "I3c");
        Outcome<ReceiptResponse> r = operations.receipt(c.mm, "rcpt-" + UUID.randomUUID(),
            new ReceiptRequest(c.warehouse, null, null, List.of(new ReceiptLine(item, new BigDecimal("4")))));
        UUID id = r.body().movements().get(0).id();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> {
                try { operations.reverse(c.mm, "rev-" + UUID.randomUUID(), id, new ReverseRequest("duplicate click")); return "ok"; }
                catch (ConflictException e) { return "conflict"; }
            }));
        }
        int ok = 0;
        for (Future<String> f : fs) if (f.get(60, TimeUnit.SECONDS).equals("ok")) ok++;
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(onHand(item, c.warehouse)).isEqualByComparingTo("0");
        assertInvariant(item, c.warehouse);
    }

    @Test
    void ledgerRowsAreImmutableAndBalancesCannotGoNegative() {
        Ctx c = ctx();
        UUID item = item(c, "I4");
        receive(c, item, "2");
        assertThatThrownBy(() -> jdbc.update("UPDATE inventory_movements SET note='x' WHERE item_id=?", item))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM inventory_movements WHERE item_id=?", item))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE stock_balances SET on_hand = on_hand - 3 WHERE item_id=?", item))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE stock_balances SET reserved = 5 WHERE item_id=?", item))
            .isInstanceOf(DataAccessException.class);
        // corrections without a reason are rejected by the database too
        assertThatThrownBy(() -> jdbc.update("INSERT INTO inventory_movements (tenant_id,item_id,warehouse_id,type,movement_kind,quantity,balance_after) "
            + "VALUES (?,?,?,'stock_in','adjustment_in',1,3)", c.h.tenant(), item, c.warehouse)).isInstanceOf(DataAccessException.class);
        assertThat(onHand(item, c.warehouse)).isEqualByComparingTo("2");
    }

    @Test
    void oppositeLockOrderAcrossManyItemsNeverDeadlocks() throws Exception {
        Ctx c = ctx();
        UUID a = item(c, "LA"), b = item(c, "LB");
        receive(c, a, "1000");
        receive(c, b, "1000");
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final boolean flip = i % 2 == 0;
            fs.add(pool.submit(() -> {
                go.await();
                List<StockCommand> cmds = flip
                    ? List.of(cmd(c, a, MovementKind.issue, "1"), cmd(c, b, MovementKind.issue, "1"))
                    : List.of(cmd(c, b, MovementKind.issue, "1"), cmd(c, a, MovementKind.issue, "1"));
                try { tx().executeWithoutResult(s -> ledger.apply(cmds)); return true; }
                catch (org.springframework.dao.PessimisticLockingFailureException e) { return false; } // at most a retryable 409
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : fs) if (f.get(120, TimeUnit.SECONDS)) ok++;
        pool.shutdown();
        assertThat(ok).isEqualTo(50);
        assertThat(onHand(a, c.warehouse)).isEqualByComparingTo(String.valueOf(1000 - ok));
        assertInvariant(a, c.warehouse);
        assertInvariant(b, c.warehouse);
    }

    @Test
    void reservationsReduceAvailableStock() {
        Ctx c = ctx();
        UUID item = item(c, "I5");
        receive(c, item, "10");
        tx().executeWithoutResult(s -> ledger.reserve(c.warehouse, item, new BigDecimal("8")));
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> ledger.apply(List.of(cmd(c, item, MovementKind.issue, "3")))))
            .isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> ledger.reserve(c.warehouse, item, new BigDecimal("3"))))
            .isInstanceOf(InsufficientStockException.class);
        tx().executeWithoutResult(s -> ledger.apply(List.of(new StockCommand(c.h.tenant(), c.warehouse, item, MovementKind.issue,
            new BigDecimal("5"), true, null, null, null, null, null, null, null, c.mm, null))));
        assertThat(jdbc.queryForObject("SELECT reserved FROM stock_balances WHERE item_id=?", BigDecimal.class, item)).isEqualByComparingTo("3");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> ledger.release(c.warehouse, item, new BigDecimal("4"))))
            .isInstanceOf(ConflictException.class);
        tx().executeWithoutResult(s -> ledger.release(c.warehouse, item, new BigDecimal("3")));
        assertInvariant(item, c.warehouse);
    }

    @Test
    void ledgerIsMandatoryTransactional() {
        Ctx c = ctx();
        UUID item = item(c, "I6");
        assertThatThrownBy(() -> ledger.apply(List.of(cmd(c, item, MovementKind.receipt, "1"))))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void tenantIsolationAndScopeReturn404() {
        Ctx a = ctx(), b = ctx();
        UUID itemA = item(a, "ISO");
        receive(a, itemA, "3");
        // user of tenant B, tenant context B
        TenantContext.set(b.h.tenant());
        assertThatThrownBy(() -> catalog.getWarehouse(b.mm, a.warehouse)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> itemService.get(b.mm, itemA)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> operations.receipt(b.mm, "x-" + UUID.randomUUID(),
            new ReceiptRequest(a.warehouse, null, null, List.of(new ReceiptLine(itemA, BigDecimal.ONE))))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(itemService.list(b.mm, null, null, null, null, null, 0, 50, null).getContent()).extracting(i -> i.getId()).doesNotContain(itemA);
        assertThat(queries.balances(b.mm, null, null, null, 0, 50, null).getContent()).isEmpty();
        // same tenant, other center scope: tenant A manager cannot see tenant B center either
        TenantContext.set(a.h.tenant());
        assertThat(itemService.list(a.mm, null, null, null, null, null, 0, 50, null).getContent()).extracting(i -> i.getId()).contains(itemA);
        // technician: no balances, catalog without stock figures
        assertThatThrownBy(() -> queries.balances(a.te, null, null, null, 0, 10, null)).isInstanceOf(AccessDeniedException.class);
        assertThat(itemService.get(a.te, itemA).getTotalOnHand()).isNull();
        assertThat(itemService.get(a.mm, itemA).getTotalOnHand()).isEqualByComparingTo("3");
        assertThat(queries.movements(a.te, null, null, null, null, null, null, 0, 10, null).getContent()).isEmpty();
    }

    private void awaitCount(String sql, UUID arg, int expected) {
        for (int i = 0; i < 100; i++) {
            if (jdbc.queryForObject(sql, Integer.class, arg) == expected) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        assertThat(jdbc.queryForObject(sql, Integer.class, arg)).isEqualTo(expected);
    }

    private void awaitRearm(UUID item) {
        for (int i = 0; i < 100; i++) {
            if (jdbc.queryForObject("SELECT low_stock_notified_at IS NULL FROM inventory_items WHERE id = ?", Boolean.class, item)) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        assertThat(jdbc.queryForObject("SELECT low_stock_notified_at IS NULL FROM inventory_items WHERE id = ?", Boolean.class, item)).isTrue();
    }

    @Test
    void quantityRulesAndLowStockAlert() {
        Ctx c = ctx();
        UUID item = item(c, "I7");
        jdbc.update("UPDATE inventory_items SET reorder_level = 5 WHERE id = ?", item);
        assertThatThrownBy(() -> receive(c, item, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receive(c, item, "1.2345")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receive(c, item, "1000001")).isInstanceOf(IllegalArgumentException.class);
        receive(c, item, "4");
        assertThat(itemService.lowStock(c.mm, null, 0, 50).getContent()).extracting(i -> i.getId()).contains(item);
        // one notification to the warehouse keeper and the manager, sent once
        awaitCount("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'LOW_STOCK'", c.wh.getId(), 1);
        receive(c, item, "0.5"); // still low: no second alert
        receive(c, item, "10");
        assertThat(itemService.lowStock(c.mm, null, 0, 50).getContent()).extracting(i -> i.getId()).doesNotContain(item);
        awaitRearm(item);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'LOW_STOCK'", Integer.class, c.wh.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'LOW_STOCK'", Integer.class, c.mm.getId())).isEqualTo(1);
    }
}
