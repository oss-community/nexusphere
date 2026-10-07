package com.nexusphere.ledger.evidence.domain.model;

import java.time.Instant;
import java.util.Map;

public record EvidenceSubmission(
        Instant occurredAt,
        String agentId,
        String principalId,
        String action,
        String target,
        Decision decision,
        String reason,
        String delegationId,
        String inputHash,
        String outputHash,
        Outcome outcome,
        String correlationId,
        Map<String, String> attributes) {
}
