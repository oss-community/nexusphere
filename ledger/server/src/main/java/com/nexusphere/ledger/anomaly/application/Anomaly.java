package com.nexusphere.ledger.anomaly.application;

import java.time.Instant;
import java.util.UUID;

public record Anomaly(UUID id, String agentId, AnomalyStatistics.Kind kind, Status status, double observed,
                      double expected, double score, Instant windowStart, Instant windowEnd, Instant detectedAt,
                      Instant resolvedAt, UUID evidenceId, UUID resolvedEvidenceId) {

    public enum Status {
        OPEN,
        RESOLVED
    }
}
