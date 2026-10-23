package com.smms.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Removes idempotency records older than 7 days. */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupJob {

    static final long RETENTION_DAYS = 7;

    private final IdempotencyRecordRepository repo;

    @Scheduled(cron = "${app.idempotency.cleanup-cron:0 47 3 * * *}")
    @Transactional
    public void purgeDaily() {
        int n = repo.deleteOlderThan(Instant.now().minusSeconds(RETENTION_DAYS * 86400));
        log.info("Idempotency cleanup: {} old records removed", n);
    }
}
