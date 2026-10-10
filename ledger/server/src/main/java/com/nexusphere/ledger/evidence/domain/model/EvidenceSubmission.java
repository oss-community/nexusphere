package com.nexusphere.ledger.evidence.domain.model;

import com.nexusphere.ledger.chain.AgentModel;

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
        Map<String, String> attributes,
        AgentModel model) {

    public EvidenceSubmission(Instant occurredAt, String agentId, String principalId, String action, String target,
                              Decision decision, String reason, String delegationId, String inputHash,
                              String outputHash, Outcome outcome, String correlationId,
                              Map<String, String> attributes) {
        this(occurredAt, agentId, principalId, action, target, decision, reason, delegationId, inputHash, outputHash,
                outcome, correlationId, attributes, null);
    }
}
