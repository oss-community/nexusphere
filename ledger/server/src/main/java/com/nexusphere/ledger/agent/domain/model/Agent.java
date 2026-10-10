package com.nexusphere.ledger.agent.domain.model;

import com.nexusphere.ledger.chain.SigningKeys;

import java.security.PublicKey;
import java.time.Instant;

public record Agent(String agentId, String name, String ownerId, AgentStatus status, String keyPrefix,
                    Instant createdAt, String signingKey, String signingKeyId, Instant signingKeySetAt) {

    public Agent(String agentId, String name, String ownerId, AgentStatus status, String keyPrefix,
                 Instant createdAt) {
        this(agentId, name, ownerId, status, keyPrefix, createdAt, null, null, null);
    }

    public PublicKey publicSigningKey() {
        return signingKey == null ? null : SigningKeys.decodePublic(signingKey);
    }

    public boolean active() {
        return status == AgentStatus.ACTIVE;
    }
}
