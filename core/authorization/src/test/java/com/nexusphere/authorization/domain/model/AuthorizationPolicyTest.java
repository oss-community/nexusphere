package com.nexusphere.authorization.domain.model;

import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.FederationContext;
import com.nexusphere.shared.id.NetworkId;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationPolicyTest {

    private final NetworkId home = NetworkId.newId();
    private final NetworkId other = NetworkId.newId();
    private final FederationContext agreements = new FederationContext(UUID.randomUUID(),
            Set.of("AGREEMENT_CREATION", "CAPABILITY_DISCOVERY"));

    private AuthorizationPolicy.Outcome evaluate(String action, Set<Role> roles, NetworkId target,
                                                 FederationContext federation, UUID trust) {
        return AuthorizationPolicy.evaluate(new AuthorizationPolicy.Facts(action, roles, Ownership.NONE, home, target, true,
                federation, trust));
    }

    @Test
    void roleGrantsActionsInsideTheHomeNetwork() {
        AuthorizationPolicy.Outcome outcome = evaluate(Actions.AGREEMENT_PROPOSE,
                EnumSet.of(Role.MEMBER, Role.AGREEMENT_MANAGER, Role.NETWORK_ADMINISTRATOR), home, null, null);

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.reason()).isEqualTo("ROLE_GRANTED");
        assertThat(outcome.matchedRole()).isEqualTo(Role.AGREEMENT_MANAGER);
        assertThat(evaluate(Actions.AGREEMENT_PROPOSE, EnumSet.of(Role.MEMBER), home, null, null).reason())
                .isEqualTo("NO_AUTHORITY");
        assertThat(evaluate("payment:send", EnumSet.of(Role.NETWORK_ADMINISTRATOR), home, null, null).reason())
                .isEqualTo("UNKNOWN_ACTION");
    }

    @Test
    void crossNetworkActionsNeedFederationScopeTrustAndAuthority() {
        Set<Role> manager = EnumSet.of(Role.MEMBER, Role.AGREEMENT_MANAGER);
        UUID trust = UUID.randomUUID();

        assertThat(evaluate(Actions.AGREEMENT_PROPOSE, manager, other, null, trust).reason())
                .isEqualTo("FEDERATION_REQUIRED");
        assertThat(evaluate(Actions.TRANSACTION_INITIATE, EnumSet.of(Role.TRANSACTION_OPERATOR), other, agreements,
                trust).reason()).isEqualTo("FEDERATION_SCOPE_VIOLATION");
        assertThat(evaluate(Actions.ROLE_ASSIGN, EnumSet.of(Role.NETWORK_ADMINISTRATOR), other, agreements, trust)
                .reason()).isEqualTo("FEDERATION_SCOPE_VIOLATION");
        assertThat(evaluate(Actions.AGREEMENT_PROPOSE, manager, other, agreements, null).reason())
                .isEqualTo("TRUST_REQUIRED");
        assertThat(evaluate(Actions.AGREEMENT_PROPOSE, EnumSet.of(Role.MEMBER), other, agreements, trust).reason())
                .isEqualTo("NO_AUTHORITY");
        assertThat(evaluate(Actions.AGREEMENT_PROPOSE, manager, other, agreements, trust).allowed()).isTrue();
    }

    @Test
    void ownersManageTheirOwnResourcesOnlyAtHome() {
        assertThat(AuthorizationPolicy.evaluate(new AuthorizationPolicy.Facts(Actions.CAPABILITY_PUBLISH,
                EnumSet.of(Role.MEMBER), Ownership.ORGANIZATION, home, home, true, null, null)).reason())
                .isEqualTo("OWNERSHIP");
        assertThat(AuthorizationPolicy.evaluate(new AuthorizationPolicy.Facts(Actions.AGREEMENT_MANAGE,
                EnumSet.of(Role.MEMBER), Ownership.IDENTITY, home, home, true, null, null)).allowed()).isFalse();
        assertThat(AuthorizationPolicy.evaluate(new AuthorizationPolicy.Facts(Actions.TRANSACTION_EXECUTE,
                EnumSet.of(Role.MEMBER), Ownership.IDENTITY, home, home, true, null, null)).reason())
                .isEqualTo("OWNERSHIP");
        assertThat(AuthorizationPolicy.evaluate(new AuthorizationPolicy.Facts(Actions.TRANSACTION_EXECUTE,
                EnumSet.of(Role.MEMBER), Ownership.ORGANIZATION, home, home, true, null, null)).reason())
                .isEqualTo("NO_AUTHORITY");
        assertThat(AuthorizationPolicy.evaluate(new AuthorizationPolicy.Facts(Actions.CAPABILITY_DISCOVER,
                EnumSet.of(Role.MEMBER), Ownership.NONE, home, other, false, agreements, UUID.randomUUID())).reason())
                .isEqualTo("TARGET_NETWORK_NOT_ACTIVE");
    }
}
