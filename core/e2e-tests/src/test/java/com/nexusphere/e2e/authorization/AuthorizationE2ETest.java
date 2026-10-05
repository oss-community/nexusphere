package com.nexusphere.e2e.authorization;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.SovereigntyApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static com.nexusphere.e2e.support.FederationApi.active;
import static com.nexusphere.e2e.support.FederationApi.propose;
import static com.nexusphere.e2e.support.FederationApi.transition;
import static com.nexusphere.e2e.support.FederationApi.trustNetwork;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationE2ETest extends E2ETestBase {

    private IdentityApi identities;
    private String networkA;
    private String networkB;
    private ApiClient adminA;
    private ApiClient adminB;
    private ApiClient alice;
    private ApiClient bob;

    @BeforeEach
    void networks() {
        SovereigntyApi sovereignty = new SovereigntyApi(api());
        identities = new IdentityApi(api());
        networkA = sovereignty.activeNetwork("Authorization A");
        networkB = sovereignty.activeNetwork("Authorization B");
        adminA = administrator(networkA, "Admin A");
        adminB = administrator(networkB, "Admin B");
        String aliceId = identities.human("Alice");
        String alicePrincipal = identities.member(networkA, aliceId);
        alice = identities.as(identities.actor(aliceId), networkA);
        String bobId = identities.human("Bob");
        identities.member(networkA, bobId);
        bob = identities.as(identities.actor(bobId), networkA);
        assertThat(assign(adminA, networkA, alicePrincipal, "AGREEMENT_MANAGER").status()).isEqualTo(201);
    }

    private ApiClient administrator(String networkId, String name) {
        String identity = identities.human(name);
        identities.administrator(networkId, identity);
        return identities.as(identities.actor(identity), networkId);
    }

    private static ApiClient.Response assign(ApiClient as, String networkId, String principalId, String role) {
        return as.post("/api/v1/networks/" + networkId + "/role-assignments",
                "{\"principalId\":\"" + principalId + "\",\"role\":\"" + role + "\"}");
    }

    private static JsonNode evaluate(ApiClient as, String action, String targetNetwork) {
        ApiClient.Response response = as.post("/api/v1/authorization/evaluate", "{\"action\":\"" + action
                + "\",\"resource\":{\"type\":\"agreement\",\"id\":\"draft\",\"networkId\":\"" + targetNetwork + "\"}}");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    @Test
    @DisplayName("E2E-SC10-01 an in-network action granted by a role is ALLOW with reason and matched role")
    void roleGrantsInNetworkAction() {
        JsonNode allowed = evaluate(alice, "agreement:propose", networkA);
        assertThat(allowed.path("result").asString()).isEqualTo("ALLOW");
        assertThat(allowed.path("reason").asString()).isEqualTo("ROLE_GRANTED");
        assertThat(allowed.path("matchedRole").asString()).isEqualTo("AGREEMENT_MANAGER");

        JsonNode denied = evaluate(bob, "agreement:propose", networkA);
        assertThat(denied.path("result").asString()).isEqualTo("DENY");
        assertThat(denied.path("reason").asString()).isEqualTo("NO_AUTHORITY");
        assertThat(evaluate(bob, "capability:discover", networkA).path("matchedRole").asString()).isEqualTo("MEMBER");

        ApiClient.Response byMember = assign(bob, networkA, alice.get("/api/v1/principal").json().path("principalId")
                .asString(), "AUDITOR");
        assertThat(byMember.status()).isEqualTo(403);
        assertThat(byMember.json().path("code").asString()).isEqualTo("NO_AUTHORITY");
        assertThat(byMember.json().path("details").path("decisionId").asString()).isNotBlank();

        String assignment = alice.get("/api/v1/networks/" + networkA + "/role-assignments?principalId="
                + alice.get("/api/v1/principal").json().path("principalId").asString()).json().get(0).path("id").asString();
        assertThat(adminA.post("/api/v1/networks/" + networkA + "/role-assignments/" + assignment + "/revoke", "")
                .json().path("status").asString()).isEqualTo("REVOKED");
        assertThat(evaluate(alice, "agreement:propose", networkA).path("reason").asString()).isEqualTo("NO_AUTHORITY");
    }

    @Test
    @DisplayName("E2E-SC10-02 the same action against another network without federation is DENY FEDERATION_REQUIRED")
    void crossNetworkNeedsFederation() {
        JsonNode denied = evaluate(alice, "agreement:propose", networkB);

        assertThat(denied.path("result").asString()).isEqualTo("DENY");
        assertThat(denied.path("reason").asString()).isEqualTo("FEDERATION_REQUIRED");
        assertThat(denied.path("targetNetworkId").asString()).isEqualTo(networkB);
    }

    @Test
    @DisplayName("E2E-SC10-03 with federation but the action outside its scope is DENY FEDERATION_SCOPE_VIOLATION")
    void actionOutsideFederationScope() {
        ApiClient.Response proposed = propose(adminA, networkA, networkB, "\"CAPABILITY_DISCOVERY\"");
        String federation = proposed.json().path("id").asString();
        transition(adminA, networkA, federation, "submit");
        transition(adminB, networkB, federation, "accept");
        trustNetwork(adminB, networkB, networkA, "\"agreement:propose\"", null);

        assertThat(evaluate(alice, "agreement:propose", networkB).path("reason").asString())
                .isEqualTo("FEDERATION_SCOPE_VIOLATION");
    }

    @Test
    @DisplayName("E2E-SC10-04 trust without a role or delegation is DENY; with both it is ALLOW")
    void trustDoesNotGrantPermission() {
        String federation = active(adminA, networkA, adminB, networkB);
        assertThat(evaluate(alice, "agreement:propose", networkB).path("reason").asString())
                .isEqualTo("TRUST_REQUIRED");
        String trust = trustNetwork(adminB, networkB, networkA, "\"agreement:propose\"", null).json().path("id")
                .asString();

        JsonNode withoutRole = evaluate(bob, "agreement:propose", networkB);
        assertThat(withoutRole.path("result").asString()).isEqualTo("DENY");
        assertThat(withoutRole.path("reason").asString()).isEqualTo("NO_AUTHORITY");

        JsonNode allowed = evaluate(alice, "agreement:propose", networkB);
        assertThat(allowed.path("result").asString()).isEqualTo("ALLOW");
        assertThat(allowed.path("federationId").asString()).isEqualTo(federation);
        assertThat(allowed.path("trustRelationshipId").asString()).isEqualTo(trust);
    }

    @Test
    @DisplayName("E2E-SC10-05 every evaluation is retrievable as an authorization decision by ID")
    void decisionsAreRetrievable() {
        JsonNode decision = evaluate(alice, "agreement:propose", networkB);
        String id = decision.path("decisionId").asString();

        ApiClient.Response own = alice.get("/api/v1/authorization/decisions/" + id);
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.json().path("reason").asString()).isEqualTo("FEDERATION_REQUIRED");
        assertThat(own.json().path("action").asString()).isEqualTo("agreement:propose");
        assertThat(adminA.get("/api/v1/authorization/decisions/" + id).status()).isEqualTo(200);
        assertThat(bob.get("/api/v1/authorization/decisions/" + id).status()).isEqualTo(404);
        assertThat(adminB.get("/api/v1/authorization/decisions/" + id).status()).isEqualTo(404);
        assertThat(api().asOperator().get("/api/v1/authorization/roles").json().valueStream()
                .map(role -> role.path("role").asString())).contains("NETWORK_ADMINISTRATOR", "AGREEMENT_MANAGER");
    }
}
