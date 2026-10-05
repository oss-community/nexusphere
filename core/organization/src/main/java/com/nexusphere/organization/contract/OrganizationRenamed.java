package com.nexusphere.organization.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record OrganizationRenamed(UUID eventId, Instant occurredAt, NetworkId network, OrganizationId organization, String name)
        implements DomainEvent {

    public OrganizationRenamed(Instant occurredAt, NetworkId network, OrganizationId organization, String name) {
        this(UUID.randomUUID(), occurredAt, network, organization, name);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(network);
    }
}
