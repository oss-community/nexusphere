package com.nexusphere.ledger.anomaly.application;

import com.nexusphere.ledger.anomaly.application.AnomalyStatistics.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AnomalyStatisticsTest {

    private static final long[] STEADY = {20, 22, 18, 21, 19, 20, 20, 22, 18, 20};

    @Test
    void volumeFiresOnlyWellAboveTheBaseline() {
        assertThat(AnomalyStatistics.volume(23, STEADY, 3, 10)).isEmpty();
        AnomalyStatistics.Finding finding = AnomalyStatistics.volume(40, STEADY, 3, 10).orElseThrow();
        assertThat(finding.kind()).isEqualTo(Kind.VOLUME);
        assertThat(finding.expected()).isEqualTo(20.0);
        assertThat(finding.score()).isCloseTo(20 / Math.sqrt(1.8), within(1e-9));
    }

    @Test
    void volumeNeedsTheMinimumEventsAndUsesAtLeastOneDeviation() {
        assertThat(AnomalyStatistics.volume(9, new long[24], 3, 10)).isEmpty();
        assertThat(AnomalyStatistics.volume(10, new long[24], 3, 10).orElseThrow().score()).isEqualTo(10.0);
        assertThat(AnomalyStatistics.volume(2, new long[24], 3, 10)).isEmpty();
    }

    @Test
    void ratesCompareAgainstTheBaselineShare() {
        assertThat(AnomalyStatistics.rate(Kind.DENIALS, 3, 50, 50, 1000, 3, 10)).isEmpty();
        AnomalyStatistics.Finding finding = AnomalyStatistics.rate(Kind.DENIALS, 20, 50, 50, 1000, 3, 10)
                .orElseThrow();
        assertThat(finding.observed()).isEqualTo(0.4);
        assertThat(finding.expected()).isEqualTo(0.05);
        assertThat(finding.score()).isCloseTo(0.35 / Math.sqrt(0.05 * 0.95 / 50), within(1e-9));
    }

    @Test
    void ratesNeedEnoughEventsAndAtLeastOneHit() {
        assertThat(AnomalyStatistics.rate(Kind.FAILURES, 5, 9, 0, 0, 3, 10)).isEmpty();
        assertThat(AnomalyStatistics.rate(Kind.FAILURES, 0, 100, 0, 0, 3, 10)).isEmpty();
        assertThat(AnomalyStatistics.rate(Kind.FAILURES, 10, 10, 0, 0, 3, 10).orElseThrow().expected())
                .isEqualTo(0.01);
    }
}
