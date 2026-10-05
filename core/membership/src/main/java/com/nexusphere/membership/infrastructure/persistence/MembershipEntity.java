package com.nexusphere.membership.infrastructure.persistence;

import com.nexusphere.membership.domain.model.MembershipRole;
import com.nexusphere.membership.domain.model.MembershipStatus;
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
@Table(schema = "membership", name = "membership")
class MembershipEntity {

    @Id
    private UUID id;

    @Column(name = "identity_id", nullable = false)
    private UUID identityId;

    @Column(name = "network_id", nullable = false)
    private UUID networkId;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Version
    private Long version;

    protected MembershipEntity() {
    }

    MembershipEntity(UUID id, UUID identityId, UUID networkId, UUID organizationId, MembershipRole role,
                     MembershipStatus status, Instant joinedAt, Instant terminatedAt, Long version) {
        this.id = id;
        this.identityId = identityId;
        this.networkId = networkId;
        this.organizationId = organizationId;
        this.role = role;
        this.status = status;
        this.joinedAt = joinedAt;
        this.terminatedAt = terminatedAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    UUID getIdentityId() {
        return identityId;
    }

    UUID getNetworkId() {
        return networkId;
    }

    UUID getOrganizationId() {
        return organizationId;
    }

    MembershipRole getRole() {
        return role;
    }

    MembershipStatus getStatus() {
        return status;
    }

    Instant getJoinedAt() {
        return joinedAt;
    }

    Instant getTerminatedAt() {
        return terminatedAt;
    }

    Long getVersion() {
        return version;
    }
}
