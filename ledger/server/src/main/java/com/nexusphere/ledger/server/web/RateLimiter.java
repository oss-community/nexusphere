package com.nexusphere.ledger.server.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

final class RateLimiter {

    static final int MAX_KEYS = 100_000;
    static final String OVERFLOW_KEY = "overflow";

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private final int perMinute;
    private final int burst;
    private final LongSupplier nanoTime;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    RateLimiter(int perMinute, int burst, LongSupplier nanoTime) {
        this.perMinute = perMinute;
        this.burst = burst < 1 ? Math.max(perMinute, 1) : burst;
        this.nanoTime = nanoTime;
    }

    boolean enabled() {
        return perMinute > 0;
    }

    long acquire(String key) {
        if (!enabled()) {
            return 0;
        }
        long now = nanoTime.getAsLong();
        if (buckets.size() >= MAX_KEYS && !buckets.containsKey(key)) {
            evictFull(now);
            if (buckets.size() >= MAX_KEYS) {
                key = OVERFLOW_KEY;
            }
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(burst, now)).take(now);
    }

    int size() {
        return buckets.size();
    }

    private void evictFull(long now) {
        buckets.values().removeIf(bucket -> bucket.full(now));
    }

    private final class Bucket {

        private double tokens;
        private long refilledAt;

        Bucket(double tokens, long refilledAt) {
            this.tokens = tokens;
            this.refilledAt = refilledAt;
        }

        synchronized long take(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            double nanosPerToken = (double) NANOS_PER_MINUTE / perMinute;
            return (long) Math.ceil((1 - tokens) * nanosPerToken / 1_000_000_000d);
        }

        synchronized boolean full(long now) {
            refill(now);
            return tokens >= burst;
        }

        private void refill(long now) {
            double added = (now - refilledAt) * (double) perMinute / NANOS_PER_MINUTE;
            tokens = Math.min(burst, tokens + added);
            refilledAt = now;
        }
    }
}
