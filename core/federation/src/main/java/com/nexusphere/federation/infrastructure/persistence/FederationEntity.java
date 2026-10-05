package com.nexusphere.federation.infrastructure.persistence;

import com.nexusphere.federation.domain.model.FederationStatus;
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
@Table(schema = "federation", name = "federation")
class FederationEntity {

    @Id
    private UUID id;

    @Column(name = "proposer_network_id", nullable = false)
    private UUID proposerNetworkId;

    @Column(name = "partner_network_id", nullable = false)
    private UUID partnerNetworkId;

    @Column(nullable = false, columnDefinition = "text")
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FederationStatus status;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    @Column(name = "suspended_by")
    private UUID suspendedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected FederationEntity() {
    }

    FederationEntity(UUID id, UUID proposerNetworkId, UUID partnerNetworkId, String scopes, FederationStatus status,
                     Instant effectiveFrom, Instant effectiveUntil, UUID suspendedBy, Instant createdAt,
                     Instant updatedAt, Long version) {
        this.id = id;
        this.proposerNetworkId = proposerNetworkId;
        this.partnerNetworkId = partnerNetworkId;
        this.scopes = scopes;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
        this.suspendedBy = suspendedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    UUID getProposerNetworkId() {
        return proposerNetworkId;
    }

    UUID getPartnerNetworkId() {
        return partnerNetworkId;
    }

    String getScopes() {
        return scopes;
    }

    FederationStatus getStatus() {
        return status;
    }

    Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    Instant getEffectiveUntil() {
        return effectiveUntil;
    }

    UUID getSuspendedBy() {
        return suspendedBy;
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
