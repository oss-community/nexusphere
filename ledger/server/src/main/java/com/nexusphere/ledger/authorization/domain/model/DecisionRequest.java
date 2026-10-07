package com.nexusphere.ledger.authorization.domain.model;

import java.util.Map;

public record DecisionRequest(
        String agentId,
        String principalId,
        String action,
        String target,
        String inputHash,
        String correlationId,
        Map<String, String> attributes) {
}
