package com.nexusphere.authorization.domain.model;

import com.nexusphere.authorization.contract.DelegationEvidence;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.PrincipalId;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

public final class DelegationPolicy {

    public record Facts(PrincipalId principalId, NetworkId homeNetwork, String action, NetworkId targetNetwork,
                        String resourceType, String capabilityTypeCode, Instant now) {
    }

    private DelegationPolicy() {
    }

    public static AuthorizationPolicy.Outcome evaluate(DelegationEvidence delegation, Facts facts,
                                                       Optional<Set<Role>> delegatorRoles) {
        if (!delegation.networkId().equals(facts.homeNetwork())
                || !delegation.delegatePrincipalId().equals(facts.principalId())) {
            return AuthorizationPolicy.Outcome.deny("DELEGATION_INVALID");
        }
        if ("REVOKED".equals(delegation.status())) {
            return AuthorizationPolicy.Outcome.deny("DELEGATION_REVOKED");
        }
        if ("SUSPENDED".equals(delegation.status())) {
            return AuthorizationPolicy.Outcome.deny("DELEGATION_SUSPENDED");
        }
        if (facts.now().isBefore(delegation.validFrom())) {
            return AuthorizationPolicy.Outcome.deny("DELEGATION_NOT_YET_VALID");
        }
        if (isExpired(delegation, facts.now())) {
            return AuthorizationPolicy.Outcome.deny("DELEGATION_EXPIRED");
        }
        if (!delegation.actions().contains(facts.action())) {
            return AuthorizationPolicy.Outcome.deny("DELEGATION_SCOPE_VIOLATION");
        }
        if (!satisfiesConstraints(delegation, facts)) {
            return AuthorizationPolicy.Outcome.deny("DELEGATION_CONSTRAINT_VIOLATION");
        }
        Optional<Role> role = delegatorRoles.flatMap(roles -> AuthorizationPolicy.matchingRole(roles, facts.action()));
        if (role.isEmpty()) {
            return AuthorizationPolicy.Outcome.deny("DELEGATOR_AUTHORITY_LOST");
        }
        return new AuthorizationPolicy.Outcome(true, "DELEGATION_GRANTED", role.get());
    }

    public static boolean isCurrent(DelegationEvidence delegation, Instant now) {
        return "ACTIVE".equals(delegation.status()) && !isExpired(delegation, now);
    }

    private static boolean isExpired(DelegationEvidence delegation, Instant now) {
        return delegation.validUntil() != null && !now.isBefore(delegation.validUntil());
    }

    private static boolean satisfiesConstraints(DelegationEvidence delegation, Facts facts) {
        boolean capability = delegation.capabilityTypes().isEmpty()
                || (facts.capabilityTypeCode() != null && delegation.capabilityTypes().contains(facts.capabilityTypeCode()));
        boolean network = delegation.networks().isEmpty() || delegation.networks().contains(facts.targetNetwork());
        boolean resource = delegation.resourceTypes().isEmpty()
                || (facts.resourceType() != null && delegation.resourceTypes().contains(facts.resourceType()));
        return capability && network && resource;
    }
}
