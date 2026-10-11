package com.nexusphere.ledger.authorization.domain.model;

import com.nexusphere.ledger.chain.AgentModel;

import java.util.Map;

public record DecisionRequest(
        String agentId,
        String principalId,
        String action,
        String target,
        String inputHash,
        String correlationId,
        Map<String, String> attributes,
        AgentModel model) {

    public DecisionRequest(String agentId, String principalId, String action, String target, String inputHash,
                           String correlationId, Map<String, String> attributes) {
        this(agentId, principalId, action, target, inputHash, correlationId, attributes, null);
    }

    public DecisionRequest withAttributes(Map<String, String> replaced) {
        return new DecisionRequest(agentId, principalId, action, target, inputHash, correlationId, replaced, model);
    }
}
