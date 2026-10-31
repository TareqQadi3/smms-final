package com.smms.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Turns in-app notifications into delivery rows for users who opted in. It reads the notifications
 * table instead of hooking {@code NotificationService}: in-app creation, WebSocket push and delivery
 * are fully decoupled, a failure here can never affect an in-app notification.
 */
@Component
@Slf4j
public class DeliveryEnqueuer {

    private final DeliveryStore store;
    private final NotificationProperties props;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DeliveryEnqueuer(DeliveryStore store, NotificationProperties props) {
        this(store, props, Clock.systemUTC());
    }

    public DeliveryEnqueuer(DeliveryStore store, NotificationProperties props, Clock clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    public int enqueue() {
        Instant now = clock.instant();
        int n = store.enqueueNew(now, now.minus(Duration.ofHours(props.getLookbackHours())));
        if (n > 0) log.info("Queued {} notification deliveries", n);
        return n;
    }
}
