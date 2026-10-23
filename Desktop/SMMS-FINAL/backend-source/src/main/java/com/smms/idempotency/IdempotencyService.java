package com.smms.idempotency;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.smms.entity.User;
import com.smms.exception.ConflictException;
import com.smms.exception.WorkflowException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;

/**
 * Generic idempotency for state-changing endpoints.
 *
 * <p>The outer method is deliberately NOT transactional (it refuses to run inside a transaction).
 * The first request with a (user, scope, key) triple runs in an inner REQUIRES_NEW transaction:
 * it inserts the {@code idempotency_records} row first, then runs the operation and stores
 * {@code result_ref}; operation and record commit or roll back together. A concurrent duplicate
 * blocks on the unique index until the first transaction ends: if it committed, the insert fails
 * with a unique violation (outside of any rollback-only transaction of ours) and the duplicate is
 * answered from a fresh read transaction (replay when the fingerprint matches, 422 when not); if it
 * rolled back, the duplicate simply becomes the first request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repo;
    private final PlatformTransactionManager txManager;

    /** The operation's outcome: the response body and the id stored as {@code result_ref} (may be null). */
    public record Result<R>(R body, UUID resultRef) {}

    /** What the caller gets back; {@code replayed} drives the {@code Idempotent-Replayed} header. */
    public record Outcome<R>(R body, boolean replayed) {}

    /**
     * @param actor       caller (record owner and tenant)
     * @param scope       operation name, at most 60 chars (e.g. "stock.receipt")
     * @param rawKey      the {@code Idempotency-Key} header value (validated here: 400 when malformed)
     * @param fingerprint {@link #fingerprint} of the request and its path ids
     * @param operation   runs inside the inner transaction, receives the idempotency record id
     *                    (store it on the rows it writes if needed) and returns body + result ref
     * @param replayLoader rebuilds the body from the stored result ref in a read transaction
     */
    public <R> Outcome<R> execute(User actor, String scope, String rawKey, String fingerprint,
                                  Function<UUID, Result<R>> operation,
                                  Function<UUID, R> replayLoader) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "IdempotencyService.execute must be called outside a transaction");
        }
        String key = IdempotencyKeyValidator.require(rawKey);

        TransactionTemplate inner = new TransactionTemplate(txManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        boolean[] inserted = {false};
        try {
            Result<R> result = inner.execute(status -> {
                UUID id = UUID.randomUUID();
                // the unique index (user, scope, key) arbitrates concurrent duplicates here
                repo.saveAndFlush(IdempotencyRecord.builder()
                    .id(id).tenantId(actor.getTenantId()).userId(actor.getId())
                    .scope(scope).idemKey(key).fingerprint(fingerprint).build());
                inserted[0] = true;
                Result<R> r = operation.apply(id);
                if (r.resultRef() != null) {
                    IdempotencyRecord rec = repo.findById(id).orElseThrow();
                    rec.setResultRef(r.resultRef());
                    repo.saveAndFlush(rec);
                }
                return r;
            });
            return new Outcome<>(result.body(), false);
        } catch (DataIntegrityViolationException e) {
            // A violation raised by the operation itself is a real error, not a duplicate request
            if (inserted[0]) throw e;
            return replay(actor, scope, key, fingerprint, replayLoader);
        }
    }

    private <R> Outcome<R> replay(User actor, String scope, String key, String fingerprint,
                                  Function<UUID, R> replayLoader) {
        TransactionTemplate read = new TransactionTemplate(txManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        return read.execute(status -> {
            IdempotencyRecord rec = repo.findByUserIdAndScopeAndIdemKey(actor.getId(), scope, key)
                .orElseThrow(() -> new ConflictException("Request is being processed, retry"));
            if (!rec.getFingerprint().equals(fingerprint)) {
                throw new WorkflowException(
                    IdempotencyKeyValidator.HEADER + " was already used with a different request");
            }
            return new Outcome<>(replayLoader.apply(rec.getResultRef()), true);
        });
    }

    /**
     * Fingerprint of a request: SHA-256 (hex) of the canonical JSON of the request body (object keys
     * sorted at every level) followed by the path ids. Same request gives the same value regardless
     * of JSON field order.
     */
    public static String fingerprint(ObjectMapper mapper, Object request, Object... pathIds) {
        try {
            ObjectMapper canonical = mapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
            // plain maps/lists first so that nested objects are key-sorted too
            Object tree = canonical.convertValue(request, Object.class);
            StringBuilder sb = new StringBuilder(canonical.writeValueAsString(tree));
            for (Object id : pathIds) sb.append('|').append(id);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }
}
