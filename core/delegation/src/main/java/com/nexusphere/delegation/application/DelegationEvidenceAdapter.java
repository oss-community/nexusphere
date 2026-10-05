package com.nexusphere.delegation.application;

import com.nexusphere.authorization.contract.DelegationEvidence;
import com.nexusphere.authorization.contract.DelegationEvidencePort;
import com.nexusphere.delegation.domain.model.Delegation;
import com.nexusphere.delegation.domain.repository.DelegationRepository;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class DelegationEvidenceAdapter implements DelegationEvidencePort {

    private final DelegationRepository delegations;

    DelegationEvidenceAdapter(DelegationRepository delegations) {
        this.delegations = delegations;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DelegationEvidence> find(UUID delegationId) {
        return delegations.findById(delegationId).map(DelegationEvidenceAdapter::evidence);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DelegationEvidence> findGrantedTo(NetworkId networkId, PrincipalId delegatePrincipalId) {
        return delegations.findGrantedTo(networkId, delegatePrincipalId).stream()
                .map(DelegationEvidenceAdapter::evidence).toList();
    }

    private static DelegationEvidence evidence(Delegation delegation) {
        return new DelegationEvidence(delegation.id(), delegation.networkId(), delegation.delegatorPrincipalId(),
                delegation.delegatePrincipalId(), delegation.actions(), delegation.constraints().capabilityTypes(),
                delegation.constraints().networks(), delegation.constraints().resourceTypes(), delegation.validFrom(),
                delegation.validUntil().orElse(null), delegation.status().name(), delegation.createdAt());
    }
}
