package com.nexusphere.trust.infrastructure.persistence;

import com.nexusphere.trust.domain.model.PartyType;
import com.nexusphere.trust.domain.model.TrustLevel;
import com.nexusphere.trust.domain.model.TrustStatus;
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
@Table(schema = "trust", name = "trust_relationship")
class TrustRelationshipEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private PartyType sourceType;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "source_network_id", nullable = false)
    private UUID sourceNetworkId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private PartyType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "target_network_id", nullable = false)
    private UUID targetNetworkId;

    @Column(nullable = false, columnDefinition = "text")
    private String scopes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrustLevel level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TrustStatus status;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_until")
    private Instant effectiveUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    private Long version;

    protected TrustRelationshipEntity() {
    }

    TrustRelationshipEntity(UUID id, PartyType sourceType, UUID sourceId, UUID sourceNetworkId, PartyType targetType,
                            UUID targetId, UUID targetNetworkId, String scopes, TrustLevel level, TrustStatus status,
                            Instant effectiveFrom, Instant effectiveUntil, Instant createdAt, Instant revokedAt,
                            Long version) {
        this.id = id;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.sourceNetworkId = sourceNetworkId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetNetworkId = targetNetworkId;
        this.scopes = scopes;
        this.level = level;
        this.status = status;
        this.effectiveFrom = effectiveFrom;
        this.effectiveUntil = effectiveUntil;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    PartyType getSourceType() {
        return sourceType;
    }

    UUID getSourceId() {
        return sourceId;
    }

    UUID getSourceNetworkId() {
        return sourceNetworkId;
    }

    PartyType getTargetType() {
        return targetType;
    }

    UUID getTargetId() {
        return targetId;
    }

    UUID getTargetNetworkId() {
        return targetNetworkId;
    }

    String getScopes() {
        return scopes;
    }

    TrustLevel getLevel() {
        return level;
    }

    TrustStatus getStatus() {
        return status;
    }

    Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    Instant getEffectiveUntil() {
        return effectiveUntil;
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
