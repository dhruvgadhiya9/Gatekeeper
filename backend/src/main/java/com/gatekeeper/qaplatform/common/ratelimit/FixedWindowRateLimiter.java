package com.gatekeeper.qaplatform.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts requests per key in fixed time windows.
 *
 * <p>Counts live in this process's memory, so with several backend instances each one enforces the
 * limit separately. Replace it with a shared store when the backend is scaled out (Phase 10).
 */
public class FixedWindowRateLimiter {

    private static final int PURGE_THRESHOLD = 10_000;

    private record Window(long startMillis, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long windowMillis;

    public FixedWindowRateLimiter(Clock clock, Duration window) {
        this.clock = clock;
        this.windowMillis = window.toMillis();
    }

    /** Returns true if this request is within the limit for the key. */
    public boolean tryAcquire(String key, int limit) {
        long now = clock.millis();
        purgeExpiredIfLarge(now);
        Window window = windows.compute(key, (ignored, current) ->
                current == null || now - current.startMillis() >= windowMillis
                        ? new Window(now, 1)
                        : new Window(current.startMillis(), current.count() + 1));
        return window.count() <= limit;
    }

    public long windowSeconds() {
        return windowMillis / 1000;
    }

    /** Stops a flood of distinct addresses from growing the map without limit. */
    private void purgeExpiredIfLarge(long now) {
        if (windows.size() > PURGE_THRESHOLD) {
            windows.values().removeIf(window -> now - window.startMillis() >= windowMillis);
        }
    }

    int trackedKeys() {
        return windows.size();
    }
}
