package com.nexusphere.authorization.domain.model;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.FederationContext;
import com.nexusphere.shared.id.NetworkId;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AuthorizationPolicy {

    public record Facts(String action, Set<Role> roles, boolean owner, NetworkId homeNetwork, NetworkId targetNetwork,
                        boolean targetNetworkActive, FederationContext federation, UUID trustRelationshipId) {
    }

    public record Outcome(boolean allowed, String reason, Role matchedRole) {

        public static Outcome deny(String reason) {
            return new Outcome(false, reason, null);
        }
    }

    private static final Map<String, String> FEDERATION_SCOPES = Map.of(
            Actions.CAPABILITY_DISCOVER, "CAPABILITY_DISCOVERY",
            Actions.CAPABILITY_INVOKE, "CAPABILITY_INVOCATION",
            Actions.IDENTITY_READ, "IDENTITY_VISIBILITY",
            Actions.AGREEMENT_PROPOSE, "AGREEMENT_CREATION",
            Actions.AGREEMENT_ACCEPT, "AGREEMENT_CREATION",
            Actions.AGREEMENT_MANAGE, "AGREEMENT_CREATION",
            Actions.TRANSACTION_INITIATE, "TRANSACTION_EXCHANGE",
            Actions.TRANSACTION_EXECUTE, "TRANSACTION_EXCHANGE");

    private static final Set<String> OWNER_ACTIONS = Set.of(Actions.CAPABILITY_REGISTER, Actions.CAPABILITY_PUBLISH);

    private AuthorizationPolicy() {
    }

    public static Optional<String> federationScopeFor(String action) {
        return Optional.ofNullable(FEDERATION_SCOPES.get(action));
    }

    public static boolean isCrossNetwork(Facts facts) {
        return !facts.homeNetwork().equals(facts.targetNetwork());
    }

    public static Outcome evaluate(Facts facts) {
        if (!Actions.ALL.contains(facts.action())) {
            return Outcome.deny("UNKNOWN_ACTION");
        }
        if (isCrossNetwork(facts)) {
            Optional<Outcome> federationDenial = crossNetworkDenial(facts);
            if (federationDenial.isPresent()) {
                return federationDenial.get();
            }
        }
        Optional<Role> role = matchingRole(facts.roles(), facts.action());
        if (role.isPresent()) {
            return new Outcome(true, "ROLE_GRANTED", role.get());
        }
        if (facts.owner() && !isCrossNetwork(facts) && OWNER_ACTIONS.contains(facts.action())) {
            return new Outcome(true, "OWNERSHIP", null);
        }
        return Outcome.deny("NO_AUTHORITY");
    }

    public static Optional<Role> matchingRole(Set<Role> roles, String action) {
        return roles.stream().filter(role -> role.grants(action)).min(Comparator.comparing(Role::ordinal));
    }

    private static Optional<Outcome> crossNetworkDenial(Facts facts) {
        if (!facts.targetNetworkActive()) {
            return Optional.of(Outcome.deny("TARGET_NETWORK_NOT_ACTIVE"));
        }
        Optional<String> scope = federationScopeFor(facts.action());
        if (scope.isEmpty()) {
            return Optional.of(Outcome.deny("FEDERATION_SCOPE_VIOLATION"));
        }
        if (facts.federation() == null) {
            return Optional.of(Outcome.deny("FEDERATION_REQUIRED"));
        }
        if (!facts.federation().scopes().contains(scope.get())) {
            return Optional.of(Outcome.deny("FEDERATION_SCOPE_VIOLATION"));
        }
        if (facts.trustRelationshipId() == null) {
            return Optional.of(Outcome.deny("TRUST_REQUIRED"));
        }
        return Optional.empty();
    }
}
