package com.nexusphere.ledger.benchmarks;

import java.util.Arrays;

final class Latencies {

    private long[] nanos = new long[1024];
    private int count;
    private long errors;

    synchronized void add(long value) {
        if (count == nanos.length) {
            nanos = Arrays.copyOf(nanos, count * 2);
        }
        nanos[count++] = value;
    }

    synchronized void error() {
        errors++;
    }

    synchronized int count() {
        return count;
    }

    synchronized long errors() {
        return errors;
    }

    synchronized double percentileMillis(double percentile) {
        if (count == 0) {
            return 0;
        }
        long[] sorted = Arrays.copyOf(nanos, count);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100 * count);
        return sorted[Math.clamp(rank - 1, 0, count - 1)] / 1_000_000.0;
    }
}
