package com.nexusphere.integration.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "integration", name = "ledger_grant")
class LedgerGrantEntity {

    @Id
    @Column(name = "grant_id", length = 64)
    private String grantId;

    @Column(name = "delegation_id", nullable = false)
    private UUID delegationId;

    @Column(name = "agent_id", nullable = false, length = 200)
    private String agentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected LedgerGrantEntity() {
    }

    LedgerGrantEntity(String grantId, UUID delegationId, String agentId, Instant createdAt, Instant revokedAt) {
        this.grantId = grantId;
        this.delegationId = delegationId;
        this.agentId = agentId;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
    }

    void revoked(Instant at) {
        revokedAt = at;
    }

    String getGrantId() {
        return grantId;
    }

    UUID getDelegationId() {
        return delegationId;
    }

    String getAgentId() {
        return agentId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }
}
