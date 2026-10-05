package com.nexusphere.authorization.infrastructure.persistence;

import com.nexusphere.authorization.domain.model.Role;
import com.nexusphere.authorization.domain.model.RoleAssignmentStatus;
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
@Table(schema = "`authorization`", name = "role_assignment")
class RoleAssignmentEntity {

    @Id
    private UUID id;

    @Column(name = "network_id", nullable = false)
    private UUID networkId;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoleAssignmentStatus status;

    @Column(name = "assigned_by")
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    private Long version;

    protected RoleAssignmentEntity() {
    }

    RoleAssignmentEntity(UUID id, UUID networkId, UUID principalId, Role role, RoleAssignmentStatus status,
                         UUID assignedBy, Instant assignedAt, Instant revokedAt, Long version) {
        this.id = id;
        this.networkId = networkId;
        this.principalId = principalId;
        this.role = role;
        this.status = status;
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
        this.revokedAt = revokedAt;
        this.version = version;
    }

    UUID getId() {
        return id;
    }

    UUID getNetworkId() {
        return networkId;
    }

    UUID getPrincipalId() {
        return principalId;
    }

    Role getRole() {
        return role;
    }

    RoleAssignmentStatus getStatus() {
        return status;
    }

    UUID getAssignedBy() {
        return assignedBy;
    }

    Instant getAssignedAt() {
        return assignedAt;
    }

    Instant getRevokedAt() {
        return revokedAt;
    }

    Long getVersion() {
        return version;
    }
}
