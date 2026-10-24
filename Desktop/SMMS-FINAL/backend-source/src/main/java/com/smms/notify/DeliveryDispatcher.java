package com.smms.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sends due deliveries. Each row is claimed with a lease (crash safe, multi-node safe), sent OUTSIDE
 * any database transaction, then updated. Rules:
 * - no usable provider: row becomes {@code not_configured} (terminal), nothing is claimed as sent;
 * - no address: {@code skipped_no_address};
 * - success: {@code sent} only when the provider accepted the message;
 * - failure: backoff 1m/5m/30m/2h/6h, then {@code dead} (also immediately on a permanent error).
 */
@Component
@Slf4j
public class DeliveryDispatcher {

    private final DeliveryStore store;
    private final ProviderRegistry providers;
    private final NotificationProperties props;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryDispatcher(DeliveryStore store, ProviderRegistry providers, NotificationProperties props) {
        this(store, providers, props, Clock.systemUTC());
    }

    public DeliveryDispatcher(DeliveryStore store, ProviderRegistry providers, NotificationProperties props, Clock clock) {
        this.store = store;
        this.providers = providers;
        this.props = props;
        this.clock = clock;
    }

    /** One pass; returns the number of rows handled. Never throws for a single bad row. */
    public int dispatchOnce() {
        Instant now = clock.instant();
        List<DeliveryStore.Claimed> batch =
            store.claimDue(now, now.plusSeconds(props.getLeaseSeconds()), props.getBatchSize());
        for (DeliveryStore.Claimed c : batch) {
            try {
                handle(c);
            } catch (RuntimeException e) {
                // e.g. database hiccup while recording: the lease expires and the row is retried
                log.warn("Delivery {} could not be processed: {}", c.id(), e.getClass().getSimpleName());
            }
        }
        return batch.size();
    }

    private void handle(DeliveryStore.Claimed c) {
        NotificationProvider provider = providers.forChannel(c.channel());
        if (!provider.available()) {
            store.markTerminal(c.id(), DeliveryStatus.not_configured, provider.name(), "no provider configured for " + c.channel());
            return;
        }
        if (c.destination() == null || c.destination().isBlank()) {
            store.markTerminal(c.id(), DeliveryStatus.skipped_no_address, provider.name(), "no address on the user");
            return;
        }
        SendResult r;
        try {
            r = provider.send(new OutboundMessage(c.channel(), c.destination().trim(), c.subject(), c.body()));
        } catch (RuntimeException e) {
            r = SendResult.transientFailure(e.getClass().getSimpleName());
        }
        Instant now = clock.instant();
        if (r.accepted()) {
            store.markSent(c.id(), provider.name(), r.providerMessageId(), now);
            return;
        }
        int attemptsMade = c.attempts() + 1;
        Duration delay = r.retryable() ? props.backoffAfter(attemptsMade) : null;
        boolean dead = delay == null;
        store.markFailed(c.id(), provider.name(), ErrorSanitizer.sanitize(r.error()),
            dead ? null : now.plus(delay), dead);
    }
}
