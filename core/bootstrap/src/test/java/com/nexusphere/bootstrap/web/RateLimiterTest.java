package com.nexusphere.bootstrap.web;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong();

    @Test
    void aCallerGetsItsBurstThenWaitsForTheRefill() {
        RateLimiter limiter = new RateLimiter(60, 3, clock::get);

        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isEqualTo(1);
        assertThat(limiter.acquire("b")).isZero();

        clock.addAndGet(SECOND);

        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isEqualTo(1);
    }

    @Test
    void theBucketNeverHoldsMoreThanTheBurst() {
        RateLimiter limiter = new RateLimiter(60, 2, clock::get);
        clock.addAndGet(3600 * SECOND);

        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isZero();
        assertThat(limiter.acquire("a")).isPositive();
    }

    @Test
    void zeroTurnsTheLimitOff() {
        RateLimiter limiter = new RateLimiter(0, 0, clock::get);

        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.acquire("a")).isZero();
        }
        assertThat(limiter.size()).isZero();
    }

    @Test
    void idleCallersAreForgottenWhenTheTableIsFull() {
        RateLimiter limiter = new RateLimiter(60, 1, clock::get);
        for (int i = 0; i < RateLimiter.MAX_KEYS; i++) {
            limiter.acquire("caller-" + i);
        }
        clock.addAndGet(2 * SECOND);

        assertThat(limiter.acquire("newcomer")).isZero();
        assertThat(limiter.size()).isEqualTo(1);
    }
}
