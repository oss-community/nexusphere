package com.nexusphere.trust.application;

import com.nexusphere.authorization.contract.TrustEvidencePort;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.time.TimeProvider;
import com.nexusphere.trust.domain.model.Party;
import com.nexusphere.trust.domain.model.TrustRelationship;
import com.nexusphere.trust.domain.repository.TrustRelationshipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
class TrustEvidenceAdapter implements TrustEvidencePort {

    private final TrustRelationshipRepository relationships;
    private final TimeProvider time;

    TrustEvidenceAdapter(TrustRelationshipRepository relationships, TimeProvider time) {
        this.relationships = relationships;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findEffective(NetworkId trustingNetwork, NetworkId trustedNetwork, String scope) {
        Instant now = time.now();
        return relationships.findActive(Party.network(trustingNetwork), Party.network(trustedNetwork)).stream()
                .filter(trust -> trust.covers(scope, now)).findFirst().map(TrustRelationship::id);
    }
}
