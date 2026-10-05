package com.nexusphere.organization.infrastructure.persistence;

import com.nexusphere.organization.domain.model.OrganizationStatus;
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
@Table(schema = "organization", name = "organization")
class OrganizationEntity {

    @Id
    private UUID id;

    @Column(name = "network_id", nullable = false)
    private UUID networkId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrganizationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected OrganizationEntity() {
    }

    OrganizationEntity(UUID id, UUID networkId, String name, OrganizationStatus status,
                       Instant createdAt, Instant updatedAt, Long version) {
        this.id = id;
        this.networkId = networkId;
        this.name = name;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    UUID getNetworkId() {
        return networkId;
    }

    String getName() {
        return name;
    }

    OrganizationStatus getStatus() {
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
