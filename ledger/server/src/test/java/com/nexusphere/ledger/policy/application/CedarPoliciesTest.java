package com.nexusphere.ledger.policy.application;

import com.nexusphere.ledger.chain.AgentModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CedarPoliciesTest {

    private static final Instant MONDAY_NOON = Instant.parse("2026-10-12T12:00:00Z");

    private static CedarPolicies.Request request(String action, String target, Map<String, String> attributes) {
        return new CedarPolicies.Request("invoice-agent", "alice", action, target, null, attributes,
                new AgentModel("model-a", "1", null, null), MONDAY_NOON);
    }

    @Test
    void theDefaultPermitAllowsWhatNoPolicyForbids() {
        CedarPolicies policies = CedarPolicies.compile("""
                @id("no-wire")
                forbid(principal, action == Action::"payments.wire", resource);
                """, true);

        CedarPolicies.Verdict allowed = policies.evaluate(request("payments.pay", "acme", Map.of()));
        CedarPolicies.Verdict denied = policies.evaluate(request("payments.wire", "acme", Map.of()));

        assertThat(allowed.allowed()).isTrue();
        assertThat(allowed.policies()).containsExactly(CedarPolicies.DEFAULT_PERMIT);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.policies()).containsExactly("no-wire");
        assertThat(policies.ids()).containsExactly("no-wire");
    }

    @Test
    void theDefaultDenyAllowsOnlyWhatAPolicyPermits() {
        CedarPolicies policies = CedarPolicies.compile("""
                @id("pay-acme")
                permit(principal == Agent::"invoice-agent", action == Action::"payments.pay",
                       resource == Target::"acme");
                """, false);

        assertThat(policies.evaluate(request("payments.pay", "acme", Map.of())).policies())
                .containsExactly("pay-acme");
        CedarPolicies.Verdict other = policies.evaluate(request("payments.pay", "globex", Map.of()));
        assertThat(other.allowed()).isFalse();
        assertThat(other.policies()).isEmpty();
    }

    @Test
    void theContextCarriesAttributesTheModelAndTheTime() {
        CedarPolicies policies = CedarPolicies.compile("""
                @id("small-amounts")
                forbid(principal, action, resource)
                  when { context.attributes has amount && context.attributes.amount != "100" };
                @id("office-hours")
                forbid(principal, action, resource) unless { context.hour >= 9 && context.hour < 17 };
                @id("approved-model")
                forbid(principal, action, resource) unless { context.model.name == "model-a" };
                """, true);

        assertThat(policies.evaluate(request("payments.pay", "acme", Map.of("amount", "100"))).allowed()).isTrue();
        assertThat(policies.evaluate(request("payments.pay", "acme", Map.of("amount", "900"))).policies())
                .containsExactly("small-amounts");
        CedarPolicies.Request night = new CedarPolicies.Request("invoice-agent", "alice", "payments.pay", "acme",
                null, Map.of(), new AgentModel("model-b", "1", null, null), Instant.parse("2026-10-12T22:00:00Z"));
        assertThat(policies.evaluate(night).policies()).containsExactly("approved-model", "office-hours");
    }

    @Test
    void anEvaluationErrorDenies() {
        CedarPolicies policies = CedarPolicies.compile("""
                @id("needs-model")
                forbid(principal, action, resource) when { context.model.name == "model-x" };
                """, true);

        CedarPolicies.Request noModel = new CedarPolicies.Request("invoice-agent", "alice", "payments.pay", "acme",
                null, Map.of(), null, MONDAY_NOON);
        CedarPolicies.Verdict verdict = policies.evaluate(noModel);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.errors()).singleElement().asString().startsWith("needs-model: ");
    }

    @Test
    void aDenialAsksForApprovalOnlyWhenEveryDecidingPolicyAllowsIt() {
        CedarPolicies policies = CedarPolicies.compile("""
                @id("wire") @approval("principal")
                forbid(principal, action, resource == Target::"bank/wire_transfer");
                @id("large") @approval("any")
                forbid(principal, action, resource) when { context.attributes has large };
                @id("night") @approval("operator")
                forbid(principal, action, resource) unless { context.hour >= 9 && context.hour < 17 };
                @id("loan")
                forbid(principal, action, resource == Target::"bank/loan");
                """, true);

        assertThat(policies.evaluate(request("payments.pay", "bank/wire_transfer", Map.of())).approver())
                .isEqualTo(CedarPolicies.PRINCIPAL);
        assertThat(policies.evaluate(request("payments.pay", "acme", Map.of("large", "yes"))).approver())
                .isEqualTo(CedarPolicies.ANY);
        assertThat(policies.evaluate(request("payments.pay", "bank/wire_transfer", Map.of("large", "yes")))
                .approver()).isEqualTo(CedarPolicies.PRINCIPAL);
        assertThat(policies.evaluate(request("payments.pay", "bank/loan", Map.of())).approver()).isNull();
        assertThat(policies.evaluate(request("payments.pay", "acme", Map.of())).approver()).isNull();
        CedarPolicies.Request night = new CedarPolicies.Request("invoice-agent", "alice", "payments.pay",
                "bank/wire_transfer", null, Map.of(), null, Instant.parse("2026-10-12T22:00:00Z"));
        assertThat(policies.evaluate(night).approver()).isNull();
    }

    @Test
    void invalidPolicySetsAreRejected() {
        assertThatThrownBy(() -> CedarPolicies.compile("permit(principal, action", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("The policies cannot be parsed");
        assertThatThrownBy(() -> CedarPolicies.compile("""
                @id("a") permit(principal, action, resource);
                @id("a") forbid(principal, action, resource);
                """, true)).hasMessage("The policy id a is used twice");
        assertThatThrownBy(() -> CedarPolicies.compile("permit(principal == ?principal, action, resource);", true))
                .hasMessage("Policy templates are not supported");
        assertThatThrownBy(() -> CedarPolicies.compile("""
                @id("ledger-default-permit") forbid(principal, action, resource);
                """, true)).hasMessage("The policy id ledger-default-permit is reserved");
    }
}
