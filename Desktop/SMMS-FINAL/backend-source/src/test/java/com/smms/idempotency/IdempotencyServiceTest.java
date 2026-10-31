package com.smms.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.entity.User;
import com.smms.exception.ConflictException;
import com.smms.exception.WorkflowException;
import com.smms.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The repository is an in-memory fake with a unique (user, scope, key) index, so races are real. */
class IdempotencyServiceTest {

    private static final String KEY = "key-12345678";

    private IdempotencyRecordRepository repo;
    private PlatformTransactionManager tm;
    private IdempotencyService service;
    private final Map<String, IdempotencyRecord> store = new ConcurrentHashMap<>();
    private User actor;

    private static String idx(UUID user, String scope, String key) { return user + "|" + scope + "|" + key; }

    @BeforeEach
    void setUp() {
        repo = mock(IdempotencyRecordRepository.class);
        tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new IdempotencyService(repo, tm);
        actor = new User();
        actor.setId(UUID.randomUUID());
        actor.setTenantId(UUID.randomUUID());
        actor.setRole(UserRole.warehouse);

        when(repo.saveAndFlush(any(IdempotencyRecord.class))).thenAnswer(i -> {
            IdempotencyRecord r = i.getArgument(0);
            IdempotencyRecord existing = store.putIfAbsent(idx(r.getUserId(), r.getScope(), r.getIdemKey()), r);
            // saving the same row again is an update (result_ref), a different row is a unique violation
            if (existing != null && existing != r) {
                throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
            }
            return r;
        });
        when(repo.findById(any(UUID.class))).thenAnswer(i -> store.values().stream()
            .filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(repo.findByUserIdAndScopeAndIdemKey(any(), anyString(), anyString())).thenAnswer(i ->
            Optional.ofNullable(store.get(idx(i.getArgument(0), i.getArgument(1), i.getArgument(2)))));
    }

    @AfterEach
    void clean() {
        // all tests run outside a transaction: nothing may leak into the synchronisation manager
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private IdempotencyService.Outcome<String> run(String key, String fingerprint, AtomicInteger executions,
                                                    UUID resultRef) {
        return service.execute(actor, "stock.receipt", key, fingerprint,
            recordId -> {
                executions.incrementAndGet();
                return new IdempotencyService.Result<>("body-" + resultRef, resultRef);
            },
            ref -> "replayed-" + ref);
    }

    @Test
    void firstRequestRunsTheOperationAndStoresTheResultRef() {
        AtomicInteger n = new AtomicInteger();
        UUID ref = UUID.randomUUID();

        IdempotencyService.Outcome<String> out = run(KEY, "fp1", n, ref);

        assertThat(out.replayed()).isFalse();
        assertThat(out.body()).isEqualTo("body-" + ref);
        assertThat(n.get()).isEqualTo(1);
        IdempotencyRecord rec = store.get(idx(actor.getId(), "stock.receipt", KEY));
        assertThat(rec.getResultRef()).isEqualTo(ref);
        assertThat(rec.getFingerprint()).isEqualTo("fp1");
        assertThat(rec.getTenantId()).isEqualTo(actor.getTenantId());
        assertThat(rec.getUserId()).isEqualTo(actor.getId());
        // the inner work ran in its own REQUIRES_NEW transaction and committed
        verify(tm, atLeastOnce()).commit(any());
        verify(tm, never()).rollback(any());
    }

    @Test
    void operationReceivesTheRecordId() {
        UUID[] seen = new UUID[1];
        service.execute(actor, "s", KEY, "fp", id -> { seen[0] = id; return new IdempotencyService.Result<>("x", null); },
            ref -> "y");
        assertThat(store.get(idx(actor.getId(), "s", KEY)).getId()).isEqualTo(seen[0]);
    }

    @Test
    void replayWithSameFingerprintDoesNotRunAgain() {
        AtomicInteger n = new AtomicInteger();
        UUID ref = UUID.randomUUID();
        run(KEY, "fp1", n, ref);

        IdempotencyService.Outcome<String> again = run(KEY, "fp1", n, UUID.randomUUID());

        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo("replayed-" + ref);
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentFingerprintIs422() {
        AtomicInteger n = new AtomicInteger();
        run(KEY, "fp1", n, UUID.randomUUID());
        assertThatThrownBy(() -> run(KEY, "fp-other", n, UUID.randomUUID()))
            .isInstanceOf(WorkflowException.class).hasMessageContaining("different request");
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void sameKeyInAnotherScopeOrByAnotherUserIsIndependent() {
        AtomicInteger n = new AtomicInteger();
        run(KEY, "fp1", n, UUID.randomUUID());
        service.execute(actor, "stock.issue", KEY, "fp1",
            id -> { n.incrementAndGet(); return new IdempotencyService.Result<>("a", null); }, r -> "b");
        User other = new User();
        other.setId(UUID.randomUUID());
        service.execute(other, "stock.receipt", KEY, "fp1",
            id -> { n.incrementAndGet(); return new IdempotencyService.Result<>("a", null); }, r -> "b");
        assertThat(n.get()).isEqualTo(3);
    }

    @Test
    void malformedOrMissingKeyIs400AndNothingRuns() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> run("bad key!", "fp", n, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(null, "fp", n, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(n.get()).isZero();
        verifyNoInteractions(repo);
    }

    @Test
    void failedOperationRollsBackAndAllowsRetry() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> service.execute(actor, "s", KEY, "fp",
            id -> { throw new WorkflowException("boom"); }, r -> "x")).isInstanceOf(WorkflowException.class);
        verify(tm).rollback(any());
        // the fake store is not transactional; emulate the rollback of the insert
        store.clear();
        IdempotencyService.Outcome<String> retry = service.execute(actor, "s", KEY, "fp",
            id -> { n.incrementAndGet(); return new IdempotencyService.Result<>("ok", null); }, r -> "x");
        assertThat(retry.replayed()).isFalse();
        assertThat(n.get()).isEqualTo(1);
    }

    @Test
    void integrityViolationRaisedByTheOperationItselfIsNotTreatedAsReplay() {
        assertThatThrownBy(() -> service.execute(actor, "s", KEY, "fp",
            id -> { throw new DataIntegrityViolationException("other unique constraint"); }, r -> "x"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateWhoseOwnerVanishedIs409() {
        // insert fails but the row is already gone again (cleanup/rollback race)
        when(repo.saveAndFlush(any(IdempotencyRecord.class)))
            .thenThrow(new DataIntegrityViolationException("dup"));
        assertThatThrownBy(() -> run(KEY, "fp", new AtomicInteger(), null))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void refusesToRunInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> run(KEY, "fp", new AtomicInteger(), null))
                .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void concurrentDuplicatesExecuteExactlyOnce() throws Exception {
        int threads = 12;
        AtomicInteger executions = new AtomicInteger();
        UUID ref = UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IdempotencyService.Outcome<String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.execute(actor, "stock.issue", KEY, "fp",
                    id -> {
                        executions.incrementAndGet();
                        // keep the first transaction open long enough for the others to collide
                        try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return new IdempotencyService.Result<>("body", ref);
                    },
                    r -> "replayed-" + r);
            }));
        }
        start.countDown();
        int replays = 0;
        for (Future<IdempotencyService.Outcome<String>> f : futures) {
            IdempotencyService.Outcome<String> o = f.get(10, TimeUnit.SECONDS);
            if (o.replayed()) replays++;
        }
        pool.shutdownNow();
        assertThat(executions.get()).isEqualTo(1);
        assertThat(replays).isEqualTo(threads - 1);
    }

    // ── fingerprint ──────────────────────────────────────

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void fingerprintIgnoresKeyOrderAtEveryLevel() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        a.put("inner", new LinkedHashMap<>(Map.of("p", 1, "q", 2)));
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("q", 2);
        inner.put("p", 1);
        b.put("inner", inner);
        b.put("x", 1);
        assertThat(IdempotencyService.fingerprint(json, a)).isEqualTo(IdempotencyService.fingerprint(json, b));
    }

    @Test
    void fingerprintChangesWithBodyOrPathIds() {
        UUID id = UUID.randomUUID();
        String base = IdempotencyService.fingerprint(json, Map.of("q", 1), id);
        assertThat(base).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(IdempotencyService.fingerprint(json, Map.of("q", 2), id)).isNotEqualTo(base);
        assertThat(IdempotencyService.fingerprint(json, Map.of("q", 1), UUID.randomUUID())).isNotEqualTo(base);
        assertThat(IdempotencyService.fingerprint(json, Map.of("q", 1), id)).isEqualTo(base);
    }

    record Body(String b, int a) {}

    @Test
    void fingerprintWorksForPojosAndRecords() {
        assertThat(IdempotencyService.fingerprint(json, new Body("x", 1)))
            .isEqualTo(IdempotencyService.fingerprint(json, Map.of("a", 1, "b", "x")));
    }
}
