package com.nexusphere.ledger.agent.domain.model;

import java.time.Instant;

public record Agent(String agentId, String name, String ownerId, AgentStatus status, String keyPrefix,
                    Instant createdAt) {

    public boolean active() {
        return status == AgentStatus.ACTIVE;
    }
}
