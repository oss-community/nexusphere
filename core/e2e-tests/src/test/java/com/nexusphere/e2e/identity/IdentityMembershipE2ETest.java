package com.nexusphere.e2e.identity;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.SovereigntyApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityMembershipE2ETest extends E2ETestBase {

    private SovereigntyApi sovereignty;
    private IdentityApi identities;

    @BeforeEach
    void setUp() {
        sovereignty = new SovereigntyApi(api());
        identities = new IdentityApi(api());
    }

    @Test
    @DisplayName("E2E-SC07-01 human, agent and machine identities are created with their types and owners")
    void threeIdentityTypes() {
        String network = sovereignty.activeNetwork("Identities");
        String acme = sovereignty.organization(network, "Acme");

        JsonNode human = identities.create("HUMAN", "Alice", null, null).json();
        ApiClient.Response agent = api().post("/api/v1/identities", "{\"type\":\"AGENT\",\"displayName\":\"Buyer Agent\","
                + "\"owningNetworkId\":\"" + network + "\",\"owningOrganizationId\":\"" + acme + "\","
                + "\"agentProvider\":\"Anthropic\",\"agentModel\":\"local\"}");
        JsonNode machine = identities.create("MACHINE", "CNC-7", network, acme).json();
        ApiClient.Response unowned = identities.create("AGENT", "Orphan", null, null);

        assertThat(human.path("type").asString()).isEqualTo("HUMAN");
        assertThat(human.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(human.path("owningOrganizationId").isNull()).isTrue();
        assertThat(agent.status()).isEqualTo(201);
        assertThat(agent.json().path("type").asString()).isEqualTo("AGENT");
        assertThat(agent.json().path("owningOrganizationId").asString()).isEqualTo(acme);
        assertThat(agent.json().path("agentProvider").asString()).isEqualTo("Anthropic");
        assertThat(machine.path("type").asString()).isEqualTo("MACHINE");
        assertThat(machine.path("owningOrganizationId").asString()).isEqualTo(acme);
        assertThat(unowned.status()).isEqualTo(400);
        assertThat(unowned.json().path("code").asString()).isEqualTo("OWNING_ORGANIZATION_REQUIRED");

        identities.member(network, human.path("id").asString());
        identities.member(network, agent.json().path("id").asString());
        identities.member(network, machine.path("id").asString());
        IdentityApi.Actor alice = identities.actor(human.path("id").asString());
        JsonNode members = identities.as(alice).get("/api/v1/networks/" + network + "/identities").json();
        assertThat(members.valueStream().map(member -> member.path("type").asString()))
                .containsExactlyInAnyOrder("HUMAN", "AGENT", "MACHINE");
    }

    @Test
    @DisplayName("E2E-SC07-02 one identity gets an independent principal context in each of its networks")
    void independentPrincipalPerNetwork() {
        String networkA = sovereignty.activeNetwork("Principal A");
        String networkB = sovereignty.activeNetwork("Principal B");
        String alice = identities.human("Alice");
        String membershipA = identities.member(networkA, alice);
        identities.member(networkB, alice);
        IdentityApi.Actor actor = identities.actor(alice);

        JsonNode inA = identities.as(actor, networkA).get("/api/v1/principal").json();
        JsonNode inB = identities.as(actor, networkB).get("/api/v1/principal").json();

        assertThat(inA.path("identityId").asString()).isEqualTo(alice);
        assertThat(inB.path("identityId").asString()).isEqualTo(alice);
        assertThat(inA.path("networkId").asString()).isEqualTo(networkA);
        assertThat(inB.path("networkId").asString()).isEqualTo(networkB);
        assertThat(inA.path("principalId").asString()).isNotEqualTo(inB.path("principalId").asString());

        api().post("/api/v1/networks/" + networkA + "/memberships/" + membershipA + "/terminate", "");

        assertThat(identities.as(actor, networkA).get("/api/v1/principal").status()).isEqualTo(403);
        assertThat(identities.as(actor, networkB).get("/api/v1/principal").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("E2E-SC07-03 a network context without an active membership is 403")
    void networkWithoutMembershipIsForbidden() {
        String home = sovereignty.activeNetwork("Home");
        String foreign = sovereignty.activeNetwork("Foreign");
        String alice = identities.human("Alice");
        identities.member(home, alice);
        IdentityApi.Actor actor = identities.actor(alice);

        ApiClient.Response response = identities.as(actor, foreign).get("/api/v1/principal");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().path("code").asString()).isEqualTo("NETWORK_ACCESS_DENIED");
        assertThat(identities.as(actor).get("/api/v1/principal").json().path("code").asString())
                .isEqualTo("NETWORK_CONTEXT_REQUIRED");
    }

    @Test
    @DisplayName("E2E-SC07-04 a suspended identity's token is rejected for every action")
    void suspendedIdentityIsRejected() {
        String network = sovereignty.activeNetwork("Suspension");
        String alice = identities.human("Alice");
        identities.member(network, alice);
        String secret = identities.secret(alice);
        IdentityApi.Actor actor = new IdentityApi.Actor(alice,
                identities.token(alice, secret).json().path("accessToken").asString());

        api().post("/api/v1/identities/" + alice + "/suspend", "");

        for (ApiClient.Response response : new ApiClient.Response[]{
                identities.as(actor, network).get("/api/v1/principal"),
                identities.as(actor).get("/api/v1/networks/" + network + "/identities"),
                identities.as(actor).get("/api/v1/platform")}) {
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.json().path("code").asString()).isEqualTo("INVALID_TOKEN");
        }
        assertThat(identities.token(alice, secret).status()).isEqualTo(401);

        api().post("/api/v1/identities/" + alice + "/activate", "");
        assertThat(identities.as(actor, network).get("/api/v1/principal").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("E2E-SC07-05 tokens need a valid credential and principal endpoints need a token")
    void authenticationIsRequired() {
        String network = sovereignty.activeNetwork("Auth");
        String alice = identities.human("Alice");
        identities.member(network, alice);
        identities.secret(alice);

        ApiClient.Response wrongSecret = identities.token(alice, "not-the-secret");
        ApiClient.Response noToken = api().withHeader("X-Network-Id", network).get("/api/v1/principal");
        ApiClient.Response badToken = api().withHeader("Authorization", "Bearer not.a.token")
                .withHeader("X-Network-Id", network).get("/api/v1/principal");

        assertThat(wrongSecret.status()).isEqualTo(401);
        assertThat(wrongSecret.json().path("code").asString()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(noToken.status()).isEqualTo(401);
        assertThat(noToken.json().path("code").asString()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(badToken.status()).isEqualTo(401);
        assertThat(badToken.json().path("code").asString()).isEqualTo("INVALID_TOKEN");
        assertThat(badToken.header("WWW-Authenticate")).contains("Bearer");
    }

    @Test
    @DisplayName("E2E-SC07-06 memberships follow network, ownership and uniqueness rules")
    void membershipRules() {
        String network = sovereignty.activeNetwork("Rules");
        String other = sovereignty.activeNetwork("Elsewhere");
        String acme = sovereignty.organization(network, "Acme");
        String alice = identities.human("Alice");
        String robot = identities.create("MACHINE", "Robot", network, acme).json().path("id").asString();
        identities.member(network, alice);

        ApiClient.Response twice = identities.activateMembership(network, alice);
        ApiClient.Response robotElsewhere = identities.activateMembership(other, robot);
        ApiClient.Response robotHome = identities.activateMembership(network, robot);

        assertThat(twice.status()).isEqualTo(409);
        assertThat(twice.json().path("code").asString()).isEqualTo("MEMBERSHIP_ALREADY_ACTIVE");
        assertThat(robotElsewhere.status()).isEqualTo(422);
        assertThat(robotElsewhere.json().path("code").asString()).isEqualTo("MEMBERSHIP_OUTSIDE_OWNING_NETWORK");
        assertThat(robotHome.status()).isEqualTo(201);
        assertThat(robotHome.json().path("organizationId").asString()).isEqualTo(acme);

        sovereignty.transition(network, "suspend");
        String bob = identities.human("Bob");
        assertThat(identities.activateMembership(network, bob).json().path("code").asString())
                .isEqualTo("NETWORK_NOT_ACTIVE");
        assertThat(identities.create("AGENT", "Late Agent", network, acme).json().path("code").asString())
                .isEqualTo("NETWORK_NOT_ACTIVE");
    }
}
