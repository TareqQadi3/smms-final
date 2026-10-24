package com.smms.workflow;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window limiter per client key (minute window, in memory, one replica). Bounded: when the
 * table grows past MAX_KEYS, expired windows are dropped first and, if still full, an unknown
 * key is refused rather than growing without limit.
 */
@Component
public class PublicStatusRateLimiter {

    static final int MAX_KEYS = 10_000;
    private static final long WINDOW_MS = 60_000L;

    private record Window(long start, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final PublicStatusProperties props;
    private Clock clock = Clock.systemUTC();

    public PublicStatusRateLimiter(PublicStatusProperties props) { this.props = props; }

    void setClock(Clock clock) { this.clock = clock; }

    /** True when the request may proceed. */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        int limit = Math.max(1, props.getRateLimitPerMinute());
        if (windows.size() >= MAX_KEYS && !windows.containsKey(key)) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() >= WINDOW_MS);
            if (windows.size() >= MAX_KEYS) return false;
        }
        boolean[] ok = {false};
        windows.compute(key, (k, w) -> {
            if (w == null || now - w.start() >= WINDOW_MS) { ok[0] = true; return new Window(now, 1); }
            if (w.count() < limit) { ok[0] = true; return new Window(w.start(), w.count() + 1); }
            return w;
        });
        return ok[0];
    }
}
