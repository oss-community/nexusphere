package com.nexusphere.agreement.contract;

import com.nexusphere.shared.event.DomainEvent;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record AgreementChanged(UUID eventId, Instant occurredAt, UUID agreementId, NetworkId agreementNetworkId,
                               String change, String status, int version, PrincipalId principalId,
                               NetworkId principalNetworkId) implements DomainEvent {

    public AgreementChanged(Instant occurredAt, UUID agreementId, NetworkId agreementNetworkId, String change,
                            String status, int version, PrincipalId principalId, NetworkId principalNetworkId) {
        this(UUID.randomUUID(), occurredAt, agreementId, agreementNetworkId, change, status, version, principalId,
                principalNetworkId);
    }

    @Override
    public Optional<NetworkId> networkId() {
        return Optional.of(agreementNetworkId);
    }
}
