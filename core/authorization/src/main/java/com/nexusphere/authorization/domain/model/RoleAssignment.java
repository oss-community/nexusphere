package com.nexusphere.authorization.domain.model;

import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RoleAssignment {

    private final UUID id;
    private final NetworkId networkId;
    private final PrincipalId principalId;
    private final Role role;
    private RoleAssignmentStatus status;
    private final PrincipalId assignedBy;
    private final Instant assignedAt;
    private Instant revokedAt;
    private final Long version;

    private RoleAssignment(UUID id, NetworkId networkId, PrincipalId principalId, Role role,
                           RoleAssignmentStatus status, PrincipalId assignedBy, Instant assignedAt, Instant revokedAt,
                           Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.networkId = Objects.requireNonNull(networkId, "networkId must not be null");
        this.principalId = Objects.requireNonNull(principalId, "principalId must not be null");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.assignedBy = assignedBy;
        this.assignedAt = Objects.requireNonNull(assignedAt, "assignedAt must not be null");
        this.revokedAt = revokedAt;
        this.version = version;
    }

    public static RoleAssignment assign(UUID id, NetworkId networkId, PrincipalId principalId, Role role,
                                        PrincipalId assignedBy, Instant now) {
        return new RoleAssignment(id, networkId, principalId, role, RoleAssignmentStatus.ACTIVE, assignedBy, now,
                null, null);
    }

    public static RoleAssignment restore(UUID id, NetworkId networkId, PrincipalId principalId, Role role,
                                         RoleAssignmentStatus status, PrincipalId assignedBy, Instant assignedAt,
                                         Instant revokedAt, Long version) {
        return new RoleAssignment(id, networkId, principalId, role, status, assignedBy, assignedAt, revokedAt,
                version);
    }

    public void revoke(Instant now) {
        if (status == RoleAssignmentStatus.REVOKED) {
            throw new ConflictException("ROLE_ASSIGNMENT_ALREADY_REVOKED", "Role assignment " + id + " is already revoked");
        }
        status = RoleAssignmentStatus.REVOKED;
        revokedAt = now;
    }

    public boolean isActive() {
        return status == RoleAssignmentStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public NetworkId networkId() {
        return networkId;
    }

    public PrincipalId principalId() {
        return principalId;
    }

    public Role role() {
        return role;
    }

    public RoleAssignmentStatus status() {
        return status;
    }

    public Optional<PrincipalId> assignedBy() {
        return Optional.ofNullable(assignedBy);
    }

    public Instant assignedAt() {
        return assignedAt;
    }

    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    public Long version() {
        return version;
    }
}
