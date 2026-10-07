package com.nexusphere.ledger.evidence.domain.model;

public record PackageRequest(String agentId, String principalId, Long fromSequence, Long toSequence) {
}
