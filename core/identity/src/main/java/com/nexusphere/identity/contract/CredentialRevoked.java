package com.nexusphere.identity.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record CredentialRevoked(UUID eventId, Instant occurredAt, IdentityId identity, UUID credentialId,
                                String reason) implements DomainEvent {

    public CredentialRevoked(Instant occurredAt, IdentityId identity, UUID credentialId, String reason) {
        this(UUID.randomUUID(), occurredAt, identity, credentialId, reason);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.empty();
    }
}
