package com.smms.notify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background trigger of the enqueuer and dispatcher; failures are logged, never propagated. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.notifications.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryScheduler {

    private final DeliveryEnqueuer enqueuer;
    private final DeliveryDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${app.notifications.enqueue-ms:30000}", initialDelayString = "${app.notifications.enqueue-ms:30000}")
    public void enqueue() {
        try { enqueuer.enqueue(); } catch (RuntimeException e) { log.warn("Delivery enqueue failed: {}", e.getClass().getSimpleName()); }
    }

    @Scheduled(fixedDelayString = "${app.notifications.dispatch-ms:30000}", initialDelayString = "${app.notifications.dispatch-ms:30000}")
    public void dispatch() {
        try { dispatcher.dispatchOnce(); } catch (RuntimeException e) { log.warn("Delivery dispatch failed: {}", e.getClass().getSimpleName()); }
    }
}
