package com.nexusphere.ledger.evidence.domain.model;

public record EvidenceQuery(String agentId, String principalId, long afterSequence, int limit) {
}
