package com.nexusphere.delegation.infrastructure.persistence;

import com.nexusphere.delegation.domain.model.DelegationStatus;
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
@Table(schema = "delegation", name = "delegation")
class DelegationEntity {

    @Id
    private UUID id;

    @Column(name = "network_id", nullable = false)
    private UUID networkId;

    @Column(name = "delegator_principal_id", nullable = false)
    private UUID delegatorPrincipalId;

    @Column(name = "delegate_principal_id", nullable = false)
    private UUID delegatePrincipalId;

    @Column(nullable = false, columnDefinition = "text")
    private String actions;

    @Column(name = "capability_types", nullable = false, columnDefinition = "text")
    private String capabilityTypes;

    @Column(nullable = false, columnDefinition = "text")
    private String networks;

    @Column(name = "resource_types", nullable = false, columnDefinition = "text")
    private String resourceTypes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DelegationStatus status;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    private Long version;

    protected DelegationEntity() {
    }

    DelegationEntity(UUID id, UUID networkId, UUID delegatorPrincipalId, UUID delegatePrincipalId, String actions,
                     String capabilityTypes, String networks, String resourceTypes, DelegationStatus status,
                     Instant validFrom, Instant validUntil, Instant createdAt, Instant revokedAt, Long version) {
        this.id = id;
        this.networkId = networkId;
        this.delegatorPrincipalId = delegatorPrincipalId;
        this.delegatePrincipalId = delegatePrincipalId;
        this.actions = actions;
        this.capabilityTypes = capabilityTypes;
        this.networks = networks;
        this.resourceTypes = resourceTypes;
        this.status = status;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    UUID getNetworkId() {
        return networkId;
    }

    UUID getDelegatorPrincipalId() {
        return delegatorPrincipalId;
    }

    UUID getDelegatePrincipalId() {
        return delegatePrincipalId;
    }

    String getActions() {
        return actions;
    }

    String getCapabilityTypes() {
        return capabilityTypes;
    }

    String getNetworks() {
        return networks;
    }

    String getResourceTypes() {
        return resourceTypes;
    }

    DelegationStatus getStatus() {
        return status;
    }

    Instant getValidFrom() {
        return validFrom;
    }

    Instant getValidUntil() {
        return validUntil;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }

    Long getVersion() {
        return version;
    }
}
