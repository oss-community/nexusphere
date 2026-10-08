package com.nexusphere.ledger.authorization.domain.model;

public record GrantQuery(String agentId, String principalId, GrantState state, long afterSeq, int limit) {
}
