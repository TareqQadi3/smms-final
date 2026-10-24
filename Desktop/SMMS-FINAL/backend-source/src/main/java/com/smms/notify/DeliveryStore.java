package com.smms.notify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persistence port of the dispatcher (JDBC in production, in-memory in unit tests). */
public interface DeliveryStore {

    /** A claimed delivery with everything needed to send it. Address and content are never logged. */
    record Claimed(UUID id, UUID tenantId, DeliveryChannel channel, int attempts,
                   String destination, String subject, String body) {
        @Override public String toString() { return "Claimed[" + id + "," + channel + "]"; }
    }

    /**
     * Atomically picks up to {@code limit} due rows (pending or failed, next_attempt_at &lt;= now) and
     * leases them until {@code leaseUntil} so no other node or run takes them.
     */
    List<Claimed> claimDue(Instant now, Instant leaseUntil, int limit);

    void markSent(UUID id, String provider, String providerMessageId, Instant now);

    /** Records a failed attempt: attempts+1, retry at {@code nextAttemptAt} (failed) or dead when null. */
    void markFailed(UUID id, String provider, String error, Instant nextAttemptAt, boolean dead);

    /** Terminal states that involve no attempt (not_configured, skipped_no_address). */
    void markTerminal(UUID id, DeliveryStatus status, String provider, String reason);

    /** Creates pending / skipped rows for new notifications of users who opted in. Returns rows created. */
    int enqueueNew(Instant now, Instant notBefore);
}
