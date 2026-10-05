package com.nexusphere.organization.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record OrganizationDeactivated(UUID eventId, Instant occurredAt, NetworkId network, OrganizationId organization)
        implements DomainEvent {

    public OrganizationDeactivated(Instant occurredAt, NetworkId network, OrganizationId organization) {
        this(UUID.randomUUID(), occurredAt, network, organization);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
