package com.nexusphere.authorization.infrastructure.persistence;

import com.nexusphere.authorization.contract.AuthorizationDecision;
import com.nexusphere.authorization.domain.repository.AuthorizationDecisionRepository;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaAuthorizationDecisionRepository implements AuthorizationDecisionRepository {

    private final AuthorizationDecisionJpaRepository jpa;

    JpaAuthorizationDecisionRepository(AuthorizationDecisionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AuthorizationDecision save(AuthorizationDecision decision) {
        jpa.saveAndFlush(new AuthorizationDecisionEntity(decision.id(), decision.principalId().value(),
                decision.networkId().value(), decision.targetNetworkId().value(), decision.action(),
                decision.resourceType(), decision.resourceId(), decision.allowed(), decision.reason(),
                decision.matchedRole(), decision.delegationId(), decision.federationId(),
                decision.trustRelationshipId(), decision.decidedAt()));
        return decision;
    }

    @Override
    public Optional<AuthorizationDecision> findById(UUID id) {
        return jpa.findById(id).map(entity -> new AuthorizationDecision(entity.getId(),
                new PrincipalId(entity.getPrincipalId()), new NetworkId(entity.getNetworkId()),
                new NetworkId(entity.getTargetNetworkId()), entity.getAction(), entity.getResourceType(),
                entity.getResourceId(), entity.isAllowed(), entity.getReason(), entity.getMatchedRole(),
                entity.getDelegationId(), entity.getFederationId(), entity.getTrustRelationshipId(),
                entity.getDecidedAt()));
    }
}
