package com.nexusphere.e2e.discovery;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.CapabilityApi;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.SovereigntyApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static com.nexusphere.e2e.support.CapabilityApi.body;
import static com.nexusphere.e2e.support.CapabilityApi.published;
import static com.nexusphere.e2e.support.CapabilityApi.withdraw;
import static com.nexusphere.e2e.support.FederationApi.active;
import static com.nexusphere.e2e.support.FederationApi.transition;
import static com.nexusphere.e2e.support.FederationApi.trustNetwork;
import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryE2ETest extends E2ETestBase {

    private static final String DISCOVER = "\"capability:discover\"";

    private IdentityApi identities;
    private String networkA;
    private String networkB;
    private String networkC;
    private ApiClient adminA;
    private ApiClient adminB;
    private ApiClient adminC;
    private ApiClient agentA;
    private ApiClient memberB;
    private String organizationB;
    private String cnc;
    private String logistics;
    private String federatedCnc;
    private String networkCnc;
    private String privateCnc;
    private String agentLogistics;
    private String agentB;

    @BeforeEach
    void discoverySetUp() {
        SovereigntyApi sovereignty = new SovereigntyApi(api());
        CapabilityApi capabilityApi = new CapabilityApi(api());
        identities = new IdentityApi(api());
        networkA = sovereignty.activeNetwork("Discovery A");
        networkB = sovereignty.activeNetwork("Discovery B");
        networkC = sovereignty.activeNetwork("Discovery C");
        adminA = administrator(networkA, "Admin A");
        adminB = administrator(networkB, "Admin B");
        adminC = administrator(networkC, "Admin C");
        cnc = capabilityApi.type(CapabilityApi.uniqueCode("manufacturing.cnc"), CapabilityApi.CNC_SCHEMA);
        logistics = capabilityApi.type(CapabilityApi.uniqueCode("logistics.transport"), CapabilityApi.CNC_SCHEMA);

        String organizationA = sovereignty.organization(networkA, "Organization A");
        String agentId = identities.owned("AGENT", "Agent A", networkA, organizationA);
        identities.member(networkA, agentId, organizationA);
        agentA = identities.as(identities.actor(agentId), networkA);

        organizationB = sovereignty.organization(networkB, "Organization B");
        String humanB = identities.human("Human B");
        identities.member(networkB, humanB, organizationB);
        ApiClient ownerB = identities.as(identities.actor(humanB), networkB);
        agentB = identities.owned("AGENT", "Agent B", networkB, organizationB);
        identities.member(networkB, agentB, organizationB);
        String otherB = identities.human("Other B");
        identities.member(networkB, otherB, sovereignty.organization(networkB, "Organization B2"));
        memberB = identities.as(identities.actor(otherB), networkB);

        federatedCnc = published(ownerB, networkB, body("Federated CNC", cnc, null, null, "FEDERATED"));
        networkCnc = published(ownerB, networkB, body("Network CNC", cnc, null, null, "NETWORK"));
        privateCnc = published(ownerB, networkB, body("Private CNC", cnc, null, null, "PRIVATE"));
        agentLogistics = published(ownerB, networkB, body("Agent Transport", logistics, "AGENT", agentB, "FEDERATED"));
    }

    private ApiClient administrator(String networkId, String name) {
        String identity = identities.human(name);
        identities.administrator(networkId, identity);
        return identities.as(identities.actor(identity), networkId);
    }

    private static List<String> ids(ApiClient as, String networkId, String query) {
        ApiClient.Response response = as.get("/api/v1/networks/" + networkId + "/discovery/capabilities" + query);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json().valueStream().map(found -> found.path("id").asString()).toList();
    }

    private void federateAndTrust() {
        active(adminA, networkA, adminB, networkB);
        trustNetwork(adminB, networkB, networkA, DISCOVER, null);
    }

    @Test
    @DisplayName("E2E-SC12-01 local search returns NETWORK and FEDERATED capabilities, never another member's PRIVATE one")
    void localSearchRespectsVisibility() {
        assertThat(ids(memberB, networkB, "?typeCode=" + cnc)).containsExactlyInAnyOrder(federatedCnc, networkCnc);
        assertThat(ids(memberB, networkB, "?scope=LOCAL")).doesNotContain(privateCnc)
                .contains(federatedCnc, networkCnc, agentLogistics);
        assertThat(memberB.get("/api/v1/networks/" + networkB + "/discovery/capabilities/" + privateCnc).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("E2E-SC12-02 Agent A discovers an authorized capability inside Network B only with federation scope and trust")
    void federatedSearchNeedsFederationAndTrust() {
        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).isEmpty();
        String federation = active(adminA, networkA, adminB, networkB);
        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).isEmpty();
        String trust = trustNetwork(adminB, networkB, networkA, DISCOVER, null).json().path("id").asString();

        JsonNode found = agentA.get("/api/v1/networks/" + networkA + "/discovery/capabilities?typeCode=" + cnc).json();
        assertThat(found.valueStream().map(capability -> capability.path("id").asString()))
                .containsExactly(federatedCnc);
        JsonNode capability = found.get(0);
        assertThat(capability.path("originNetworkId").asString()).isEqualTo(networkB);
        assertThat(capability.path("ownerType").asString()).isEqualTo("ORGANIZATION");
        assertThat(capability.path("ownerId").asString()).isEqualTo(organizationB);
        assertThat(capability.path("federated").asBoolean()).isTrue();
        assertThat(capability.path("federationId").asString()).isEqualTo(federation);
        assertThat(capability.path("trustRelationshipId").asString()).isEqualTo(trust);
        assertThat(agentA.get("/api/v1/authorization/decisions/" + capability.path("decisionId").asString())
                .json().path("result").asString()).isEqualTo("ALLOW");
        assertThat(agentA.get("/api/v1/networks/" + networkA + "/discovery/capabilities/" + federatedCnc).json()
                .path("originNetworkName").asString()).startsWith("Discovery B");
    }

    @Test
    @DisplayName("E2E-SC12-03 filters by capability type, owner type and origin network narrow the results")
    void filtersNarrowResults() {
        federateAndTrust();

        assertThat(ids(agentA, networkA, "?typeCode=" + logistics)).containsExactly(agentLogistics);
        assertThat(ids(agentA, networkA, "?ownerType=AGENT")).containsExactly(agentLogistics);
        assertThat(ids(agentA, networkA, "?ownerType=ORGANIZATION&originNetworkId=" + networkB))
                .containsExactly(federatedCnc);
        assertThat(ids(agentA, networkA, "?scope=LOCAL")).isEmpty();
        assertThat(ids(memberB, networkB, "?organizationId=" + organizationB + "&typeCode=" + cnc))
                .containsExactlyInAnyOrder(federatedCnc, networkCnc);
        ApiClient.Response invalid = agentA.get("/api/v1/networks/" + networkA + "/discovery/capabilities?scope=GLOBAL");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.json().path("code").asString()).isEqualTo("INVALID_DISCOVERY_SCOPE");
    }

    @Test
    @DisplayName("E2E-SC04-01..03 a capability of a network without federation is never found, by search or by ID, even with trust")
    void discoveryWithoutFederationReturnsNothing() {
        String organizationC = new SovereigntyApi(api()).organization(networkC, "Organization C");
        String humanC = identities.human("Human C");
        identities.member(networkC, humanC, organizationC);
        ApiClient ownerC = identities.as(identities.actor(humanC), networkC);
        String federatedC = published(ownerC, networkC, body("C CNC", cnc, null, null, "FEDERATED"));

        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).doesNotContain(federatedC);
        ApiClient.Response byId = agentA.get("/api/v1/networks/" + networkA + "/discovery/capabilities/" + federatedC);
        ApiClient.Response random = agentA.get("/api/v1/networks/" + networkA + "/discovery/capabilities/"
                + UUID.randomUUID());
        assertThat(byId.status()).isEqualTo(404);
        assertThat(random.status()).isEqualTo(404);
        assertThat(byId.json().path("code").asString()).isEqualTo(random.json().path("code").asString());

        trustNetwork(adminC, networkC, networkA, DISCOVER, null);
        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).doesNotContain(federatedC);
    }

    @Test
    @DisplayName("E2E-SC08-03 NETWORK capabilities stay inside their network; FEDERATED ones reach federated networks")
    void visibilityAcrossNetworks() {
        federateAndTrust();

        List<String> fromA = ids(agentA, networkA, "?originNetworkId=" + networkB);
        assertThat(fromA).contains(federatedCnc, agentLogistics).doesNotContain(networkCnc, privateCnc);
        assertThat(agentA.get("/api/v1/networks/" + networkA + "/discovery/capabilities/" + networkCnc).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("E2E-SC08-04 a withdrawn capability disappears from discovery")
    void withdrawnCapabilityDisappears() {
        federateAndTrust();
        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).containsExactly(federatedCnc);

        String humanB = identities.human("Owner B2");
        identities.member(networkB, humanB, organizationB);
        assertThat(withdraw(identities.as(identities.actor(humanB), networkB), networkB, federatedCnc).status())
                .isEqualTo(200);

        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).isEmpty();
        assertThat(ids(memberB, networkB, "?typeCode=" + cnc)).containsExactly(networkCnc);
    }

    @Test
    @DisplayName("E2E-SC09-04 suspending the federation hides federated capabilities, resuming restores them and termination is final")
    void suspensionBlocksDiscovery() {
        String federation = active(adminA, networkA, adminB, networkB);
        trustNetwork(adminB, networkB, networkA, DISCOVER, null);
        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).containsExactly(federatedCnc);

        assertThat(transition(adminB, networkB, federation, "suspend").status()).isEqualTo(200);
        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).isEmpty();
        assertThat(transition(adminB, networkB, federation, "resume").status()).isEqualTo(200);
        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).containsExactly(federatedCnc);
        assertThat(transition(adminA, networkA, federation, "terminate").status()).isEqualTo(200);
        assertThat(ids(agentA, networkA, "?typeCode=" + cnc)).isEmpty();
    }

    @Test
    @DisplayName("UC-DIS-03 other active networks are listed with their federation state")
    void networksAreListed() {
        String federation = active(adminA, networkA, adminB, networkB);

        JsonNode networks = agentA.get("/api/v1/networks/" + networkA + "/discovery/networks").json();
        JsonNode b = networks.valueStream().filter(found -> found.path("networkId").asString().equals(networkB))
                .findFirst().orElseThrow();
        JsonNode c = networks.valueStream().filter(found -> found.path("networkId").asString().equals(networkC))
                .findFirst().orElseThrow();
        assertThat(b.path("federationId").asString()).isEqualTo(federation);
        assertThat(b.path("federationScopes").valueStream().map(JsonNode::asString))
                .contains("CAPABILITY_DISCOVERY");
        assertThat(c.path("federated").asBoolean()).isFalse();
        assertThat(networks.valueStream().map(found -> found.path("networkId").asString())).doesNotContain(networkA);
    }
}
