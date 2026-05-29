package com.smms.cache;

import com.smms.entity.TenantSubscription;
import com.smms.repository.TenantSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for tenant subscriptions.
 * Reduces one DB query per HTTP request down to a memory lookup for cached entries.
 *
 * TTL: 5 minutes. Cache is also invalidated on subscription changes.
 * Thread-safe via ConcurrentHashMap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionCache {

    private static final long TTL_SECONDS = 300; // 5 minutes

    private final TenantSubscriptionRepository repo;

    private record CacheEntry(TenantSubscription subscription, Instant expiresAt) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }

    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Returns the subscription for the tenant, using cache when possible. */
    public Optional<TenantSubscription> get(UUID tenantId) {
        if (tenantId == null) return Optional.empty();

        CacheEntry entry = cache.get(tenantId);
        if (entry != null && !entry.isExpired()) {
            return Optional.ofNullable(entry.subscription());
        }

        // Cache miss or expired — load from DB
        Optional<TenantSubscription> sub = repo.findByTenantId(tenantId);
        sub.ifPresentOrElse(
            s -> cache.put(tenantId, new CacheEntry(s, Instant.now().plusSeconds(TTL_SECONDS))),
            ()  -> cache.remove(tenantId)
        );
        return sub;
    }

    /** Call this after any subscription change to force refresh on next request. */
    public void invalidate(UUID tenantId) {
        cache.remove(tenantId);
        log.debug("Subscription cache invalidated for tenant {}", tenantId);
    }

    /** Periodic cleanup of expired entries to prevent memory growth. */
    public void evictExpired() {
        int before = cache.size();
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - cache.size();
        if (removed > 0) log.debug("Evicted {} expired subscription cache entries", removed);
    }
}
