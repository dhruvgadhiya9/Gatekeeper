package com.gatekeeper.qaplatform.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FixedWindowRateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(clock, Duration.ofMinutes(1));

    @Test
    void allowsUpToTheLimitThenBlocks() {
        assertThat(limiter.tryAcquire("a", 2)).isTrue();
        assertThat(limiter.tryAcquire("a", 2)).isTrue();
        assertThat(limiter.tryAcquire("a", 2)).isFalse();
    }

    @Test
    void keysAreIndependent() {
        limiter.tryAcquire("a", 1);

        assertThat(limiter.tryAcquire("a", 1)).isFalse();
        assertThat(limiter.tryAcquire("b", 1)).isTrue();
    }

    @Test
    void newWindowResetsTheCount() {
        limiter.tryAcquire("a", 1);
        limiter.tryAcquire("a", 1);

        clock.advance(Duration.ofSeconds(60));

        assertThat(limiter.tryAcquire("a", 1)).isTrue();
    }

    @Test
    void expiredKeysArePurgedSoMemoryStaysBounded() {
        for (int i = 0; i <= 10_001; i++) {
            limiter.tryAcquire("key-" + i, 1);
        }

        clock.advance(Duration.ofMinutes(2));
        limiter.tryAcquire("fresh", 1);

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
