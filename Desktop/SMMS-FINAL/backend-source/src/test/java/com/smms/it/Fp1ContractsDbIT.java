package com.smms.it;

import com.smms.entity.User;
import com.smms.entity.WorkOrder;
import com.smms.enums.SubType;
import com.smms.enums.TicketPriority;
import com.smms.enums.TicketStatus;
import com.smms.exception.WorkflowException;
import com.smms.idempotency.IdempotencyCleanupJob;
import com.smms.idempotency.IdempotencyRecordRepository;
import com.smms.idempotency.IdempotencyService;
import com.smms.repository.UserRepository;
import com.smms.repository.WorkOrderRepository;
import com.smms.service.WorkOrderService;
import com.smms.workflow.GeneratedWorkOrderSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase-0 contracts against a real PostgreSQL: Hibernate validate of the V13 mapping (happens at
 * context start), @Version, createGenerated with the DB triggers, and the idempotency service with
 * real unique-index races.
 */
class Fp1ContractsDbIT extends AbstractDbIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired WorkOrderRepository workOrders;
    @Autowired UserRepository users;
    @Autowired WorkOrderService workOrderService;
    @Autowired IdempotencyService idempotency;
    @Autowired IdempotencyRecordRepository idempotencyRecords;
    @Autowired IdempotencyCleanupJob cleanupJob;

    private TransactionTemplate tx() { return new TransactionTemplate(txManager); }

    @Test
    void flywayReachedV14() {
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success", Integer.class))
            .isGreaterThanOrEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class)).isZero();
    }

    @Test
    void versionIncrementsAndStaleWriteIsRejected() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID wo = f.workOrder(h, f.user(h, "control_room"), "new", "medium");

        // a detached copy read while the row is at version 0
        WorkOrder stale = tx().execute(s -> {
            WorkOrder w = workOrders.findById(wo).orElseThrow();
            assertThat(w.getVersion()).isZero();
            return w;
        });
        tx().executeWithoutResult(s -> workOrders.findById(wo).orElseThrow().setTitle("first edit"));
        assertThat(jdbc.queryForObject("SELECT version FROM work_orders WHERE id = ?", Long.class, wo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_emergency FROM work_orders WHERE id = ?", Boolean.class, wo)).isFalse();

        // `stale` still carries version 0 while the row is at 1: the lost update must not happen
        stale.setTitle("lost update");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> workOrders.saveAndFlush(stale)))
            .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(jdbc.queryForObject("SELECT title FROM work_orders WHERE id = ?", String.class, wo)).isEqualTo("first edit");
    }

    @Test
    void concurrentWritersOfTheSameRowOneWinsOthersGet409Material() throws Exception {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID wo = f.workOrder(h, f.user(h, "control_room"), "new", "medium");
        // fewer threads than pooled connections: every writer holds one while waiting at the barrier
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            results.add(pool.submit(() -> {
                try {
                    tx().executeWithoutResult(s -> {
                        WorkOrder w = workOrders.findById(wo).orElseThrow();
                        try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
                        w.setTitle("writer " + n);
                    });
                    return true;
                } catch (ObjectOptimisticLockingFailureException e) {
                    return false;
                }
            }));
        }
        int winners = 0;
        for (Future<Boolean> r : results) if (r.get(30, TimeUnit.SECONDS)) winners++;
        pool.shutdownNow();
        assertThat(winners).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM work_orders WHERE id = ?", Long.class, wo)).isEqualTo(1);
    }

    @Test
    void createGeneratedUsesTriggersWritesHistoryAndLinksAsset() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy h = f.hierarchy();
        UUID requesterId = f.user(h, "control_room");
        UUID assetId = f.asset(h);
        User requester = users.findById(requesterId).orElseThrow();

        UUID id = tx().execute(s -> workOrderService.createGenerated(new GeneratedWorkOrderSpec(
            h.tenant(), h.controlRoom(), SubType.preventive_maintenance, TicketPriority.high,
            "PM: pump", "d", "loc", assetId, requester, "Generated by plan")).getId());

        var row = jdbc.queryForMap("SELECT ticket_number, sla_deadline, status::text AS st, asset_id, tenant_id, version, "
            + "requested_by_id, created_by_role::text AS cr FROM work_orders WHERE id = ?", id);
        assertThat(row.get("ticket_number")).isNotNull();
        assertThat(row.get("sla_deadline")).as("set by trigger").isNotNull();
        assertThat(row.get("st")).isEqualTo(TicketStatus.NEW.name().toLowerCase());
        assertThat(row.get("asset_id")).isEqualTo(assetId);
        assertThat(row.get("tenant_id")).isEqualTo(h.tenant());
        assertThat(((Number) row.get("version")).longValue()).isZero();
        assertThat(row.get("requested_by_id")).isEqualTo(requesterId);
        var hist = jdbc.queryForMap("SELECT note, tenant_id, actor_id, stage, to_status::text AS to_st FROM ticket_history WHERE work_order_id = ?", id);
        assertThat(hist.get("note")).isEqualTo("Generated by plan");
        assertThat(hist.get("tenant_id")).isEqualTo(h.tenant());
        assertThat(hist.get("actor_id")).isEqualTo(requesterId);
        assertThat(hist.get("stage")).isEqualTo("new");
    }

    @Test
    void createGeneratedRefusesAssetOrControlRoomOfAnotherTenantAndWritesNothing() {
        DbFixtures f = new DbFixtures(jdbc);
        DbFixtures.Hierarchy a = f.hierarchy();
        DbFixtures.Hierarchy b = f.hierarchy();
        User requesterA = users.findById(f.user(a, "control_room")).orElseThrow();
        UUID assetB = f.asset(b);
        long before = jdbc.queryForObject("SELECT count(*) FROM work_orders", Long.class);

        assertThatThrownBy(() -> tx().executeWithoutResult(s -> workOrderService.createGenerated(new GeneratedWorkOrderSpec(
            a.tenant(), a.controlRoom(), SubType.civil, null, "t", null, null, assetB, requesterA, null))))
            .isInstanceOf(com.smms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> workOrderService.createGenerated(new GeneratedWorkOrderSpec(
            a.tenant(), b.controlRoom(), SubType.civil, null, "t", null, null, null, requesterA, null))))
            .isInstanceOf(com.smms.exception.ResourceNotFoundException.class);
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> workOrderService.createGenerated(new GeneratedWorkOrderSpec(
            b.tenant(), b.controlRoom(), SubType.civil, null, "t", null, null, null, requesterA, null))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work_orders", Long.class)).isEqualTo(before);
    }

    // ── idempotency with real races ──────────────────────

    private User actor() {
        DbFixtures f = new DbFixtures(jdbc);
        return users.findById(f.user(f.hierarchy(), "warehouse")).orElseThrow();
    }

    @Test
    void concurrentSameKeyExecutesOnceAndReplaysTheRest() throws Exception {
        User actor = actor();
        String key = "race-" + UUID.randomUUID();
        AtomicInteger executions = new AtomicInteger();
        UUID ref = UUID.randomUUID();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<IdempotencyService.Outcome<String>>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return idempotency.execute(actor, "it.race", key, "fp",
                    id -> {
                        executions.incrementAndGet();
                        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return new IdempotencyService.Result<>("created", ref);
                    },
                    r -> "replay:" + r);
            }));
        }
        go.countDown();
        int replays = 0;
        for (var f : fs) {
            var o = f.get(30, TimeUnit.SECONDS);
            if (o.replayed()) { replays++; assertThat(o.body()).isEqualTo("replay:" + ref); }
        }
        pool.shutdownNow();
        assertThat(executions.get()).isEqualTo(1);
        assertThat(replays).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id = ? AND idem_key = ? AND result_ref = ?",
            Long.class, actor.getId(), key, ref)).isEqualTo(1);
    }

    @Test
    void differentFingerprintIs422AndFailedOperationRollsTheRecordBack() {
        User actor = actor();
        String key = "fp-" + UUID.randomUUID();
        idempotency.execute(actor, "it.fp", key, "A", id -> new IdempotencyService.Result<>("x", null), r -> "y");
        assertThatThrownBy(() -> idempotency.execute(actor, "it.fp", key, "B",
            id -> new IdempotencyService.Result<>("x", null), r -> "y")).isInstanceOf(WorkflowException.class);

        String key2 = "rb-" + UUID.randomUUID();
        assertThatThrownBy(() -> idempotency.execute(actor, "it.rb", key2, "A",
            id -> { throw new WorkflowException("operation failed"); }, r -> "y")).isInstanceOf(WorkflowException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idem_key = ?", Long.class, key2))
            .as("record rolled back with the operation").isZero();
        var retry = idempotency.execute(actor, "it.rb", key2, "A", id -> new IdempotencyService.Result<>("ok", null), r -> "y");
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    void cleanupDeletesOnlyRecordsOlderThanSevenDays() {
        User actor = actor();
        String old = "old-" + UUID.randomUUID(), fresh = "new-" + UUID.randomUUID();
        for (String k : List.of(old, fresh)) {
            jdbc.update("INSERT INTO idempotency_records (id, tenant_id, user_id, scope, idem_key, fingerprint, created_at) "
                + "VALUES (?, ?, ?, 'it.cleanup', ?, 'f', ?)", UUID.randomUUID(), actor.getTenantId(), actor.getId(), k,
                java.sql.Timestamp.from(k.equals(old) ? java.time.Instant.now().minus(8, java.time.temporal.ChronoUnit.DAYS)
                    : java.time.Instant.now().minus(6, java.time.temporal.ChronoUnit.DAYS)));
        }
        cleanupJob.purgeDaily();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idem_key = ?", Long.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idem_key = ?", Long.class, fresh)).isEqualTo(1);
        assertThat(idempotencyRecords.findByUserIdAndScopeAndIdemKey(actor.getId(), "it.cleanup", fresh)).isPresent();
    }
}
