package com.nexusphere.e2e.security;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.SovereigntyApi;
import com.nexusphere.e2e.support.TrustedInteraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.nexusphere.e2e.support.DelegationApi.principalId;
import static com.nexusphere.e2e.support.TrustedInteraction.transactions;
import static org.assertj.core.api.Assertions.assertThat;

class ManagementAccessE2ETest extends E2ETestBase {

    private SovereigntyApi sovereignty;
    private IdentityApi identities;
    private String network;
    private String acme;
    private ApiClient administrator;
    private ApiClient member;
    private String memberIdentity;

    @BeforeEach
    void network() {
        sovereignty = new SovereigntyApi(api());
        identities = new IdentityApi(api());
        network = sovereignty.activeNetwork("Managed");
        acme = sovereignty.organization(network, "Acme");
        String admin = identities.human("Admin");
        identities.administrator(network, admin);
        administrator = identities.as(identities.actor(admin), network);
        memberIdentity = identities.human("Member");
        identities.member(network, memberIdentity, acme);
        member = identities.as(identities.actor(memberIdentity), network);
    }

    private static void assertError(ApiClient.Response response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.json().path("code").asString()).isEqualTo(code);
    }

    @Test
    @DisplayName("E2E-SC18-01 management endpoints reject requests without a token")
    void anonymousRequestsAreRejected() {
        ApiClient anonymous = api();

        assertError(anonymous.post("/api/v1/networks", "{\"name\":\"Rogue\"}"), 401, "AUTHENTICATION_REQUIRED");
        assertError(anonymous.post("/api/v1/networks/" + network + "/suspend", ""), 401, "AUTHENTICATION_REQUIRED");
        assertError(anonymous.post("/api/v1/identities", "{\"type\":\"HUMAN\",\"displayName\":\"Rogue\"}"), 401,
                "AUTHENTICATION_REQUIRED");
        assertError(anonymous.post("/api/v1/networks/" + network + "/organizations", "{\"name\":\"Rogue\"}"), 401,
                "AUTHENTICATION_REQUIRED");
        assertError(anonymous.get("/api/v1/networks/" + network + "/organizations"), 401, "AUTHENTICATION_REQUIRED");
        assertError(anonymous.post("/api/v1/networks/" + network + "/memberships",
                "{\"identityId\":\"" + memberIdentity + "\"}"), 401, "AUTHENTICATION_REQUIRED");
        assertError(anonymous.get("/api/v1/networks/" + network + "/memberships"), 401, "AUTHENTICATION_REQUIRED");
        assertError(anonymous.post("/api/v1/identities/" + memberIdentity + "/credentials", ""), 401,
                "AUTHENTICATION_REQUIRED");
        assertError(anonymous.post("/api/v1/capability-types",
                "{\"code\":\"rogue\",\"name\":\"Rogue\",\"schema\":{\"type\":\"object\"}}"), 401,
                "AUTHENTICATION_REQUIRED");
        assertError(anonymous.get("/api/v1/networks/" + network), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    @DisplayName("E2E-SC18-02 the platform operator authenticates with its own secret and acts only on the platform")
    void operatorTokenIsSeparate() {
        assertError(api().post("/api/v1/auth/operator-token", "{\"secret\":\"wrong-secret\"}"), 401,
                "INVALID_CREDENTIALS");
        ApiClient operator = api().asOperator();

        assertThat(operator.post("/api/v1/networks", "{\"name\":\"" + SovereigntyApi.unique("Operated") + "\"}")
                .status()).isEqualTo(201);
        assertThat(operator.get("/api/v1/networks/" + network + "/organizations").status()).isEqualTo(200);
        assertError(operator.withHeader("X-Network-Id", network).get("/api/v1/principal"), 401,
                "AUTHENTICATION_REQUIRED");
        assertError(api().withHeader("Authorization", "Bearer x.y.z").post("/api/v1/networks", "{}"), 401,
                "INVALID_TOKEN");
    }

    @Test
    @DisplayName("E2E-SC18-03 a network administrator manages organizations, memberships and owned identities of its own network only")
    void administratorsManageTheirNetwork() {
        String other = sovereignty.activeNetwork("Other");
        String otherOrganization = sovereignty.organization(other, "Initech");

        ApiClient.Response organization = administrator.post("/api/v1/networks/" + network + "/organizations",
                "{\"name\":\"Globex\"}");
        assertThat(organization.status()).isEqualTo(201);
        assertThat(administrator.put("/api/v1/networks/" + network + "/organizations/"
                + organization.json().path("id").asString(), "{\"name\":\"Globex 2\"}").status()).isEqualTo(200);
        ApiClient.Response agent = administrator.post("/api/v1/identities", "{\"type\":\"AGENT\",\"displayName\":"
                + "\"Agent\",\"owningNetworkId\":\"" + network + "\",\"owningOrganizationId\":\"" + acme + "\"}");
        assertThat(agent.status()).isEqualTo(201);
        String agentId = agent.json().path("id").asString();
        assertThat(administrator.post("/api/v1/identities/" + agentId + "/credentials", "").status()).isEqualTo(201);
        assertThat(administrator.post("/api/v1/networks/" + network + "/memberships", "{\"identityId\":\"" + agentId
                + "\"}").status()).isEqualTo(201);
        assertThat(administrator.post("/api/v1/identities/" + agentId + "/suspend", "").status()).isEqualTo(200);

        assertError(administrator.post("/api/v1/networks", "{\"name\":\"Mine\"}"), 403, "PLATFORM_OPERATOR_REQUIRED");
        assertError(administrator.post("/api/v1/identities", "{\"type\":\"HUMAN\",\"displayName\":\"Eve\"}"), 403,
                "PLATFORM_OPERATOR_REQUIRED");
        assertError(administrator.post("/api/v1/identities/" + memberIdentity + "/credentials", ""), 403,
                "PLATFORM_OPERATOR_REQUIRED");
        assertError(administrator.post("/api/v1/identities", "{\"type\":\"AGENT\",\"displayName\":\"Agent\","
                + "\"owningNetworkId\":\"" + other + "\",\"owningOrganizationId\":\"" + otherOrganization + "\"}"),
                403, "NETWORK_ADMINISTRATOR_REQUIRED");
        assertError(administrator.withHeader("X-Network-Id", other).post("/api/v1/networks/" + other
                + "/organizations", "{\"name\":\"Hostile\"}"), 403, "NETWORK_ADMINISTRATOR_REQUIRED");
        assertError(administrator.get("/api/v1/networks/" + other + "/memberships"), 403, "NETWORK_ACCESS_DENIED");
    }

    @Test
    @DisplayName("E2E-SC18-04 an ordinary member cannot manage the network but can rotate its own credential")
    void membersCannotManage() {
        assertError(member.post("/api/v1/networks/" + network + "/organizations", "{\"name\":\"Rogue\"}"), 403,
                "NETWORK_ADMINISTRATOR_REQUIRED");
        assertError(member.post("/api/v1/networks/" + network + "/organizations/" + acme + "/deactivate", ""), 403,
                "NETWORK_ADMINISTRATOR_REQUIRED");
        String stranger = identities.human("Stranger");
        assertError(member.post("/api/v1/networks/" + network + "/memberships",
                "{\"identityId\":\"" + stranger + "\",\"role\":\"ADMINISTRATOR\"}"), 403,
                "NETWORK_ADMINISTRATOR_REQUIRED");
        assertError(member.post("/api/v1/networks/" + network + "/suspend", ""), 403, "PLATFORM_OPERATOR_REQUIRED");
        assertError(member.post("/api/v1/identities/" + stranger + "/credentials", ""), 403,
                "PLATFORM_OPERATOR_REQUIRED");
        assertThat(member.get("/api/v1/networks/" + network + "/organizations").status()).isEqualTo(200);

        ApiClient.Response rotated = member.post("/api/v1/identities/" + memberIdentity + "/credentials", "");
        assertThat(rotated.status()).isEqualTo(201);
        assertThat(identities.token(memberIdentity, rotated.json().path("secret").asString()).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("E2E-SC15-11 a member of the providing organization without an execution role cannot execute its transactions")
    void organizationMembershipIsNotExecutionAuthority() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String agreement = world.activeAgreement();
        ApiClient.Response requested = world.agentA.post(transactions(world.networkA), "{\"agreementId\":\""
                + agreement + "\",\"type\":\"capability.invocation\"}");
        String transaction = requested.json().path("id").asString();
        String clerk = world.identities.human("Clerk B");
        world.identities.member(world.networkB, clerk, world.organizationB);
        ApiClient clerkB = world.identities.as(world.identities.actor(clerk), world.networkB);

        ApiClient.Response executed = clerkB.post(transactions(world.networkB) + "/" + transaction + "/execute", "");

        assertError(executed, 403, "NO_AUTHORITY");
        assertThat(world.adminB.get("/api/v1/audit-events?principalId=" + principalId(clerkB) + "&result=DENIED")
                .json().valueStream().map(event -> event.path("action").asString())).contains("transaction:execute");
        assertThat(world.humanB.post(transactions(world.networkB) + "/" + transaction + "/execute", "").status())
                .isEqualTo(200);
    }
}
