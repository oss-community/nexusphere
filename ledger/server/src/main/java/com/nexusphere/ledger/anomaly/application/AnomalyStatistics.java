package com.nexusphere.ledger.anomaly.application;

import java.util.Optional;

public final class AnomalyStatistics {

    public enum Kind {
        VOLUME,
        DENIALS,
        FAILURES
    }

    public record Finding(Kind kind, double observed, double expected, double score) {
    }

    private static final double MIN_RATE = 0.01;

    private AnomalyStatistics() {
    }

    public static Optional<Finding> volume(long observed, long[] baseline, double threshold, long minEvents) {
        if (observed < minEvents || baseline.length == 0) {
            return Optional.empty();
        }
        double mean = 0;
        for (long count : baseline) {
            mean += count;
        }
        mean /= baseline.length;
        double variance = 0;
        for (long count : baseline) {
            variance += (count - mean) * (count - mean);
        }
        double deviation = Math.max(Math.sqrt(variance / baseline.length), 1);
        double score = (observed - mean) / deviation;
        return score >= threshold ? Optional.of(new Finding(Kind.VOLUME, observed, mean, score)) : Optional.empty();
    }

    public static Optional<Finding> rate(Kind kind, long hits, long total, long baselineHits, long baselineTotal,
                                         double threshold, long minEvents) {
        if (total < minEvents || hits == 0) {
            return Optional.empty();
        }
        double expected = baselineTotal == 0 ? MIN_RATE : (double) baselineHits / baselineTotal;
        double p = Math.min(Math.max(expected, MIN_RATE), 1 - MIN_RATE);
        double observed = (double) hits / total;
        double score = (observed - p) / Math.sqrt(p * (1 - p) / total);
        return score >= threshold ? Optional.of(new Finding(kind, observed, expected, score)) : Optional.empty();
    }
}
