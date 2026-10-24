package com.smms.notify;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Test double of the dispatcher's persistence port. */
class InMemoryDeliveryStore implements DeliveryStore {

    static final class Row {
        UUID id = UUID.randomUUID();
        DeliveryChannel channel;
        DeliveryStatus status = DeliveryStatus.pending;
        int attempts;
        Instant nextAttemptAt;
        String destination, subject = "title", body = "body", error, provider, messageId;
    }

    final Map<UUID, Row> rows = new LinkedHashMap<>();

    Row add(DeliveryChannel ch, String destination, Instant due) {
        Row r = new Row();
        r.channel = ch; r.destination = destination; r.nextAttemptAt = due;
        rows.put(r.id, r);
        return r;
    }

    @Override public List<Claimed> claimDue(Instant now, Instant leaseUntil, int limit) {
        List<Claimed> out = new ArrayList<>();
        for (Row r : rows.values()) {
            if (out.size() >= limit) break;
            if (r.status.dispatchable() && !r.nextAttemptAt.isAfter(now)) {
                r.nextAttemptAt = leaseUntil;
                out.add(new Claimed(r.id, UUID.randomUUID(), r.channel, r.attempts, r.destination, r.subject, r.body));
            }
        }
        return out;
    }

    @Override public void markSent(UUID id, String provider, String providerMessageId, Instant now) {
        Row r = rows.get(id); r.status = DeliveryStatus.sent; r.attempts++; r.provider = provider; r.messageId = providerMessageId;
    }

    @Override public void markFailed(UUID id, String provider, String error, Instant next, boolean dead) {
        Row r = rows.get(id); r.attempts++; r.provider = provider; r.error = error;
        r.status = dead ? DeliveryStatus.dead : DeliveryStatus.failed;
        if (next != null) r.nextAttemptAt = next;
    }

    @Override public void markTerminal(UUID id, DeliveryStatus status, String provider, String reason) {
        Row r = rows.get(id); r.status = status; r.provider = provider; r.error = reason;
    }

    @Override public int enqueueNew(Instant now, Instant notBefore) { return 0; }
}
