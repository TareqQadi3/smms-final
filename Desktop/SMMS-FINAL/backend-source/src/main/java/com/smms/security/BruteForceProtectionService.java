package com.smms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory brute-force protection for login endpoints.
 *
 * Strategy:
 *   - After MAX_ATTEMPTS failed logins for a username → block for BLOCK_DURATION
 *   - Tracks per username (not IP) to avoid false positives from NAT/proxies
 *   - Counters auto-reset after BLOCK_DURATION
 *
 * Thread-safe via ConcurrentHashMap + AtomicInteger.
 */
@Slf4j
@Service
public class BruteForceProtectionService {

    private static final int  MAX_ATTEMPTS    = 5;
    private static final long BLOCK_SECONDS   = 15 * 60; // 15 minutes

    private record FailRecord(AtomicInteger count, Instant blockedUntil) {}

    private final ConcurrentHashMap<String, FailRecord> failures = new ConcurrentHashMap<>();

    /** Call on every failed login. Returns true if account is now blocked. */
    public boolean recordFailure(String username) {
        FailRecord rec = failures.compute(username.toLowerCase(), (k, v) -> {
            if (v == null) return new FailRecord(new AtomicInteger(1), null);
            v.count().incrementAndGet();
            return v;
        });
        if (rec.count().get() >= MAX_ATTEMPTS) {
            failures.put(username.toLowerCase(),
                new FailRecord(rec.count(), Instant.now().plusSeconds(BLOCK_SECONDS)));
            log.warn("SECURITY: account locked after {} failures — username={}", MAX_ATTEMPTS, mask(username));
            return true;
        }
        return false;
    }

    /** Call on successful login to reset counters. */
    public void recordSuccess(String username) {
        failures.remove(username.toLowerCase());
    }

    /**
     * Returns true if the username is currently blocked.
     * Auto-unblocks when the block window expires.
     */
    public boolean isBlocked(String username) {
        FailRecord rec = failures.get(username.toLowerCase());
        if (rec == null) return false;
        if (rec.blockedUntil() == null) return false;
        if (Instant.now().isAfter(rec.blockedUntil())) {
            failures.remove(username.toLowerCase()); // auto-unblock
            return false;
        }
        return true;
    }

    /** How many attempts remain before lockout. */
    public int attemptsRemaining(String username) {
        FailRecord rec = failures.get(username.toLowerCase());
        if (rec == null) return MAX_ATTEMPTS;
        return Math.max(0, MAX_ATTEMPTS - rec.count().get());
    }

    /** Mask sensitive username in logs (show first 3 chars only). */
    private String mask(String username) {
        if (username == null || username.length() <= 3) return "***";
        return username.substring(0, 3) + "***";
    }
}
