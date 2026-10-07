package com.nexusphere.ledger.authorization.domain.model;

public record GrantQuery(String agentId, String principalId, long afterSeq, int limit) {
}
