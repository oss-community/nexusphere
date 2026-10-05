package com.nexusphere.capability.infrastructure.persistence;

import com.nexusphere.capability.domain.model.CapabilityStatus;
import com.nexusphere.capability.domain.model.CapabilityVisibility;
import com.nexusphere.capability.domain.model.OwnerType;
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
@Table(schema = "capability", name = "capability")
class CapabilityEntity {

    @Id
    private UUID id;

    @Column(name = "network_id", nullable = false)
    private UUID networkId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private OwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "accountable_organization_id")
    private UUID accountableOrganizationId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "type_id", nullable = false)
    private UUID typeId;

    @Column(name = "type_code", nullable = false, length = 100)
    private String typeCode;

    @Column(name = "type_version", nullable = false)
    private int typeVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String specification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CapabilityVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CapabilityStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Version
    private Long version;

    protected CapabilityEntity() {
    }

    CapabilityEntity(UUID id, UUID networkId, OwnerType ownerType, UUID ownerId, UUID accountableOrganizationId,
                     String name, String description, UUID typeId, String typeCode, int typeVersion,
                     String specification, CapabilityVisibility visibility, CapabilityStatus status,
                     Instant createdAt, Instant publishedAt, Instant withdrawnAt, Long version) {
        this.id = id;
        this.networkId = networkId;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.accountableOrganizationId = accountableOrganizationId;
        this.name = name;
        this.description = description;
        this.typeId = typeId;
        this.typeCode = typeCode;
        this.typeVersion = typeVersion;
        this.specification = specification;
        this.visibility = visibility;
        this.status = status;
        this.createdAt = createdAt;
        this.publishedAt = publishedAt;
        this.withdrawnAt = withdrawnAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    UUID getNetworkId() {
        return networkId;
    }

    OwnerType getOwnerType() {
        return ownerType;
    }

    UUID getOwnerId() {
        return ownerId;
    }

    UUID getAccountableOrganizationId() {
        return accountableOrganizationId;
    }

    String getName() {
        return name;
    }

    String getDescription() {
        return description;
    }

    UUID getTypeId() {
        return typeId;
    }

    String getTypeCode() {
        return typeCode;
    }

    int getTypeVersion() {
        return typeVersion;
    }

    String getSpecification() {
        return specification;
    }

    CapabilityVisibility getVisibility() {
        return visibility;
    }

    CapabilityStatus getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    Long getVersion() {
        return version;
    }
}
