package com.nexusphere.identity.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record CredentialIssued(UUID eventId, Instant occurredAt, IdentityId identity, UUID credentialId) implements DomainEvent {

    public CredentialIssued(Instant occurredAt, IdentityId identity, UUID credentialId) {
        this(UUID.randomUUID(), occurredAt, identity, credentialId);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.empty();
    }
}
