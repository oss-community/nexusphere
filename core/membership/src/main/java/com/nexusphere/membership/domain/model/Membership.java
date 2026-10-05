package com.nexusphere.membership.domain.model;

import com.nexusphere.membership.contract.MembershipActivated;
import com.nexusphere.membership.contract.MembershipTerminated;
import com.nexusphere.shared.domain.AggregateRoot;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class Membership extends AggregateRoot<UUID> {

    private final UUID id;
    private final IdentityId identityId;
    private final NetworkId networkId;
    private final OrganizationId organizationId;
    private final MembershipRole role;
    private MembershipStatus status;
    private final Instant joinedAt;
    private Instant terminatedAt;
    private final Long version;

    private Membership(UUID id, IdentityId identityId, NetworkId networkId, OrganizationId organizationId,
                       MembershipRole role, MembershipStatus status, Instant joinedAt, Instant terminatedAt,
                       Long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.identityId = Objects.requireNonNull(identityId, "identityId must not be null");
        this.networkId = Objects.requireNonNull(networkId, "networkId must not be null");
        this.organizationId = organizationId;
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt must not be null");
        this.terminatedAt = terminatedAt;
        this.version = version;
    }

    public static Membership activate(UUID id, IdentityId identityId, NetworkId networkId,
                                      OrganizationId organizationId, MembershipRole role, Instant now) {
        Membership membership = new Membership(id, identityId, networkId, organizationId, role,
                MembershipStatus.ACTIVE, now, null, null);
        membership.registerEvent(new MembershipActivated(now, id, identityId, networkId));
        return membership;
    }

    public static Membership restore(UUID id, IdentityId identityId, NetworkId networkId, OrganizationId organizationId,
                                     MembershipRole role, MembershipStatus status, Instant joinedAt,
                                     Instant terminatedAt, Long version) {
        return new Membership(id, identityId, networkId, organizationId, role, status, joinedAt, terminatedAt,
                version);
    }

    public void terminate(Instant now) {
        if (status == MembershipStatus.TERMINATED) {
            throw new ConflictException("MEMBERSHIP_ALREADY_TERMINATED", "Membership " + id + " is already terminated");
        }
        status = MembershipStatus.TERMINATED;
        terminatedAt = now;
        registerEvent(new MembershipTerminated(now, id, identityId, networkId));
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public PrincipalId principalId() {
        return new PrincipalId(id);
    }

    @Override
    public UUID id() {
        return id;
    }

    public IdentityId identityId() {
        return identityId;
    }

    public NetworkId networkId() {
        return networkId;
    }

    public Optional<OrganizationId> organizationId() {
        return Optional.ofNullable(organizationId);
    }

    public MembershipRole role() {
        return role;
    }

    public boolean isAdministrator() {
        return role == MembershipRole.ADMINISTRATOR;
    }

    public MembershipStatus status() {
        return status;
    }

    public Instant joinedAt() {
        return joinedAt;
    }

    public Optional<Instant> terminatedAt() {
        return Optional.ofNullable(terminatedAt);
    }

    public Long version() {
        return version;
    }
}
