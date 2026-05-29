package com.smms.scheduler;

import com.smms.cache.SubscriptionCache;
import com.smms.entity.TenantSubscription;
import com.smms.enums.TenantStatus;
import com.smms.repository.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Daily job that marks ACTIVE subscriptions past their end_date as EXPIRED.
 * Runs at 02:00 every night.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionScheduler {

    private final TenantSubscriptionRepository repo;
    private final SubscriptionCache cache;

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void expireStaleSubscriptions() {
        List<TenantSubscription> expired =
            repo.findAllByStatusAndEndDateBefore(TenantStatus.ACTIVE, Instant.now());
        if (expired.isEmpty()) return;
        expired.forEach(s -> {
            s.setStatus(TenantStatus.EXPIRED);
            cache.invalidate(s.getTenantId());
        });
        repo.saveAll(expired);
        cache.evictExpired();
        log.info("Expired {} subscriptions", expired.size());
    }
}
