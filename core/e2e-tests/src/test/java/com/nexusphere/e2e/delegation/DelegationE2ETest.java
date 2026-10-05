package com.nexusphere.e2e.delegation;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.SovereigntyApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

import static com.nexusphere.e2e.support.DelegationApi.assign;
import static com.nexusphere.e2e.support.DelegationApi.delegations;
import static com.nexusphere.e2e.support.DelegationApi.evaluate;
import static com.nexusphere.e2e.support.DelegationApi.grant;
import static com.nexusphere.e2e.support.DelegationApi.granted;
import static com.nexusphere.e2e.support.DelegationApi.principalId;
import static com.nexusphere.e2e.support.DelegationApi.transition;
import static com.nexusphere.e2e.support.FederationApi.active;
import static com.nexusphere.e2e.support.FederationApi.trustNetwork;
import static org.assertj.core.api.Assertions.assertThat;

class DelegationE2ETest extends E2ETestBase {

    private static final String PROPOSE = "\"agreement:propose\"";

    private IdentityApi identities;
    private String networkA;
    private String networkB;
    private ApiClient adminA;
    private ApiClient adminB;
    private ApiClient manager;
    private ApiClient agent;
    private ApiClient bob;
    private String managerPrincipal;
    private String agentPrincipal;
    private String bobPrincipal;

    @BeforeEach
    void delegationSetUp() {
        SovereigntyApi sovereignty = new SovereigntyApi(api());
        identities = new IdentityApi(api());
        networkA = sovereignty.activeNetwork("Delegation A");
        networkB = sovereignty.activeNetwork("Delegation B");
        adminA = administrator(networkA, "Admin A");
        adminB = administrator(networkB, "Admin B");
        String organizationA = sovereignty.organization(networkA, "Organization A");

        String managerId = identities.human("Procurement Manager");
        managerPrincipal = identities.member(networkA, managerId, organizationA);
        manager = identities.as(identities.actor(managerId), networkA);
        String agentId = identities.owned("AGENT", "Procurement Agent", networkA, organizationA);
        agentPrincipal = identities.member(networkA, agentId, organizationA);
        agent = identities.as(identities.actor(agentId), networkA);
        String bobId = identities.human("Bob");
        bobPrincipal = identities.member(networkA, bobId);
        bob = identities.as(identities.actor(bobId), networkA);
        assertThat(assign(adminA, networkA, managerPrincipal, "AGREEMENT_MANAGER").status()).isEqualTo(201);
    }

    private ApiClient administrator(String networkId, String name) {
        String identity = identities.human(name);
        identities.administrator(networkId, identity);
        return identities.as(identities.actor(identity), networkId);
    }

    @Test
    @DisplayName("E2E-SC11-01 Agent A can perform action X on behalf of Organization A but not action Y, and a delegator cannot grant what it lacks")
    void delegateActsWithinGrantedActions() {
        ApiClient.Response response = grant(manager, networkA, agentPrincipal, PROPOSE, null, null);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.json().path("delegatorPrincipalId").asString()).isEqualTo(managerPrincipal);
        assertThat(response.json().path("status").asString()).isEqualTo("ACTIVE");
        String delegation = response.json().path("id").asString();

        JsonNode allowed = evaluate(agent, "agreement:propose", networkA, null, null);
        assertThat(allowed.path("result").asString()).isEqualTo("ALLOW");
        assertThat(allowed.path("reason").asString()).isEqualTo("DELEGATION_GRANTED");
        assertThat(allowed.path("delegationId").asString()).isEqualTo(delegation);
        assertThat(allowed.path("matchedRole").asString()).isEqualTo("AGREEMENT_MANAGER");
        JsonNode denied = evaluate(agent, "agreement:accept", networkA, null, null);
        assertThat(denied.path("result").asString()).isEqualTo("DENY");
        assertThat(denied.path("reason").asString()).isEqualTo("DELEGATION_SCOPE_VIOLATION");

        ApiClient.Response exceeds = grant(manager, networkA, agentPrincipal, "\"transaction:initiate\"", null, null);
        assertThat(exceeds.status()).isEqualTo(422);
        assertThat(exceeds.json().path("code").asString()).isEqualTo("DELEGATION_EXCEEDS_AUTHORITY");
        assertThat(exceeds.json().path("details").path("actions").asString()).isEqualTo("transaction:initiate");
        assertThat(grant(bob, networkA, agentPrincipal, PROPOSE, null, null).json().path("code").asString())
                .isEqualTo("DELEGATION_EXCEEDS_AUTHORITY");
        ApiClient.Response withoutGrantAuthority = grant(bob, networkA, agentPrincipal, "\"capability:discover\"",
                null, null);
        assertThat(withoutGrantAuthority.status()).isEqualTo(403);
        assertThat(withoutGrantAuthority.json().path("code").asString()).isEqualTo("NO_AUTHORITY");
    }

    @Test
    @DisplayName("E2E-SC11-02 a delegate cannot grant its delegated authority onward")
    void delegationDepthIsOne() {
        granted(manager, networkA, agentPrincipal, PROPOSE);

        ApiClient.Response onward = grant(agent, networkA, bobPrincipal, PROPOSE, null, null);
        assertThat(onward.status()).isEqualTo(422);
        assertThat(onward.json().path("code").asString()).isEqualTo("DELEGATION_DEPTH_EXCEEDED");
        ApiClient.Response grantAuthority = grant(manager, networkA, agentPrincipal, "\"delegation:grant\"", null,
                null);
        assertThat(grantAuthority.status()).isEqualTo(422);
        assertThat(grantAuthority.json().path("code").asString()).isEqualTo("DELEGATION_DEPTH_EXCEEDED");
        assertThat(grant(manager, networkA, managerPrincipal, PROPOSE, null, null).json().path("code").asString())
                .isEqualTo("DELEGATION_TO_SELF");
    }

    @Test
    @DisplayName("E2E-SC11-03 capability and network constraints limit where the delegate may act")
    void constraintsAreEnforced() {
        String federation = active(adminA, networkA, adminB, networkB);
        trustNetwork(adminB, networkB, networkA, PROPOSE, null);
        ApiClient.Response response = grant(manager, networkA, agentPrincipal, PROPOSE,
                "{\"capabilityTypes\":[\"manufacturing.cnc\"],\"networks\":[\"" + networkB + "\"]}", null);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.json().path("constraints").path("networks").get(0).asString()).isEqualTo(networkB);

        JsonNode allowed = evaluate(agent, "agreement:propose", networkB, "manufacturing.cnc", null);
        assertThat(allowed.path("result").asString()).as(allowed.toString()).isEqualTo("ALLOW");
        assertThat(allowed.path("federationId").asString()).isEqualTo(federation);
        assertThat(evaluate(agent, "agreement:propose", networkB, "logistics.transport", null).path("reason")
                .asString()).isEqualTo("DELEGATION_CONSTRAINT_VIOLATION");
        assertThat(evaluate(agent, "agreement:propose", networkA, "manufacturing.cnc", null).path("reason")
                .asString()).isEqualTo("DELEGATION_CONSTRAINT_VIOLATION");
    }

    @Test
    @DisplayName("E2E-SC11-04 once the delegator loses its role the delegate is denied DELEGATOR_AUTHORITY_LOST")
    void delegatorAuthorityIsRechecked() {
        granted(manager, networkA, agentPrincipal, PROPOSE);
        assertThat(evaluate(agent, "agreement:propose", networkA, null, null).path("result").asString())
                .isEqualTo("ALLOW");

        String assignment = adminA.get("/api/v1/networks/" + networkA + "/role-assignments?principalId="
                + managerPrincipal).json().get(0).path("id").asString();
        assertThat(adminA.post("/api/v1/networks/" + networkA + "/role-assignments/" + assignment + "/revoke", "")
                .status()).isEqualTo(200);

        JsonNode denied = evaluate(agent, "agreement:propose", networkA, null, null);
        assertThat(denied.path("result").asString()).isEqualTo("DENY");
        assertThat(denied.path("reason").asString()).isEqualTo("DELEGATOR_AUTHORITY_LOST");
    }

    @Test
    @DisplayName("E2E-SC11-05 the effective delegations list excludes revoked, suspended and expired ones")
    void effectiveListExcludesInactive() throws InterruptedException {
        String active = granted(manager, networkA, agentPrincipal, PROPOSE);
        String revoked = granted(manager, networkA, agentPrincipal, PROPOSE);
        String suspended = granted(manager, networkA, agentPrincipal, PROPOSE);
        Instant until = Instant.now().plus(Duration.ofSeconds(2));
        String expiring = grant(manager, networkA, agentPrincipal, PROPOSE, null, until.toString()).json().path("id")
                .asString();
        assertThat(transition(manager, networkA, revoked, "revoke").json().path("status").asString())
                .isEqualTo("REVOKED");
        assertThat(transition(manager, networkA, suspended, "suspend").json().path("status").asString())
                .isEqualTo("SUSPENDED");
        assertThat(transition(agent, networkA, active, "revoke").json().path("code").asString())
                .isEqualTo("DELEGATION_NOT_MANAGED");
        while (Instant.now().isBefore(until)) {
            Thread.sleep(200);
        }

        JsonNode effective = agent.get(delegations(networkA) + "?delegatePrincipalId=" + agentPrincipal
                + "&effective=true").json();
        assertThat(effective.valueStream().map(found -> found.path("id").asString())).containsExactly(active);
        JsonNode all = adminA.get(delegations(networkA) + "?delegatePrincipalId=" + agentPrincipal).json();
        assertThat(all.valueStream().map(found -> found.path("id").asString()))
                .containsExactlyInAnyOrder(active, revoked, suspended, expiring);
        assertThat(manager.get(delegations(networkA) + "/" + expiring).json().path("status").asString())
                .isEqualTo("EXPIRED");
        assertThat(bob.get(delegations(networkA) + "/" + active).status()).isEqualTo(404);
        assertThat(adminB.get("/api/v1/networks/" + networkB + "/delegations/" + active).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("E2E-SC05-01 after revocation the next proposal is denied DELEGATION_REVOKED")
    void revokedDelegationStopsApplying() {
        String delegation = granted(manager, networkA, agentPrincipal, PROPOSE);
        assertThat(evaluate(agent, "agreement:propose", networkA, null, null).path("result").asString())
                .isEqualTo("ALLOW");

        transition(manager, networkA, delegation, "revoke");

        JsonNode denied = evaluate(agent, "agreement:propose", networkA, null, null);
        assertThat(denied.path("reason").asString()).isEqualTo("DELEGATION_REVOKED");
        assertThat(denied.path("delegationId").asString()).isEqualTo(delegation);
        assertThat(transition(manager, networkA, delegation, "resume").status()).isEqualTo(409);
    }

    @Test
    @DisplayName("E2E-SC05-03 after validUntil passes the action is denied DELEGATION_EXPIRED and the delegation shows EXPIRED")
    void expiredDelegationStopsApplying() throws InterruptedException {
        Instant until = Instant.now().plus(Duration.ofSeconds(2));
        String delegation = grant(manager, networkA, agentPrincipal, PROPOSE, null, until.toString()).json()
                .path("id").asString();
        assertThat(evaluate(agent, "agreement:propose", networkA, null, null).path("result").asString())
                .isEqualTo("ALLOW");
        while (Instant.now().isBefore(until)) {
            Thread.sleep(200);
        }

        assertThat(evaluate(agent, "agreement:propose", networkA, null, null).path("reason").asString())
                .isEqualTo("DELEGATION_EXPIRED");
        assertThat(agent.get(delegations(networkA) + "/" + delegation).json().path("status").asString())
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("E2E-SC15-05 citing another principal's delegation is denied DELEGATION_INVALID")
    void anotherPrincipalsDelegationCannotBeCited() {
        String delegation = granted(manager, networkA, agentPrincipal, PROPOSE);

        JsonNode denied = evaluate(bob, "agreement:propose", networkA, null, delegation);
        assertThat(denied.path("result").asString()).isEqualTo("DENY");
        assertThat(denied.path("reason").asString()).isEqualTo("DELEGATION_INVALID");
        assertThat(evaluate(agent, "agreement:propose", networkA, null, delegation).path("reason").asString())
                .isEqualTo("DELEGATION_GRANTED");
    }
}
