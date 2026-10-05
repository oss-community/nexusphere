package com.nexusphere.identity.infrastructure.persistence;

import com.nexusphere.identity.domain.model.IdentityStatus;
import com.nexusphere.identity.domain.model.IdentityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "identity")
class IdentityEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityType type;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "owning_network_id")
    private UUID owningNetworkId;

    @Column(name = "owning_organization_id")
    private UUID owningOrganizationId;

    @Column(name = "agent_provider", length = 100)
    private String agentProvider;

    @Column(name = "agent_model", length = 100)
    private String agentModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdentityStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected IdentityEntity() {
    }

    IdentityEntity(UUID id, IdentityType type, String displayName, UUID owningNetworkId, UUID owningOrganizationId,
                   String agentProvider, String agentModel, IdentityStatus status, Instant createdAt,
                   Instant updatedAt, Long version) {
        this.id = id;
        this.type = type;
        this.displayName = displayName;
        this.owningNetworkId = owningNetworkId;
        this.owningOrganizationId = owningOrganizationId;
        this.agentProvider = agentProvider;
        this.agentModel = agentModel;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    IdentityType getType() {
        return type;
    }

    String getDisplayName() {
        return displayName;
    }

    UUID getOwningNetworkId() {
        return owningNetworkId;
    }

    UUID getOwningOrganizationId() {
        return owningOrganizationId;
    }

    String getAgentProvider() {
        return agentProvider;
    }

    String getAgentModel() {
        return agentModel;
    }

    IdentityStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    Long getVersion() {
        return version;
    }
}
