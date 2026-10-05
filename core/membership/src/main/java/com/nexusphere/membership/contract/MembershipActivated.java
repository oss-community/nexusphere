package com.nexusphere.membership.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record MembershipActivated(UUID eventId, Instant occurredAt, UUID membershipId, IdentityId identity, NetworkId network)
        implements DomainEvent {

    public MembershipActivated(Instant occurredAt, UUID membershipId, IdentityId identity, NetworkId network) {
        this(UUID.randomUUID(), occurredAt, membershipId, identity, network);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
