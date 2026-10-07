package com.nexusphere.ledger.authorization.domain.model;

import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.Outcome;

import java.time.Instant;
import java.util.UUID;

public record DecisionRecord(
        UUID id,
        String agentId,
        String principalId,
        String action,
        String target,
        Decision decision,
        ReasonCode reasonCode,
        UUID grantId,
        Instant decidedAt,
        Outcome outcome,
        UUID outcomeEvidenceId) {
}
