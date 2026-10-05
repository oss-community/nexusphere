package com.nexusphere.e2e.sovereignty;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.SovereigntyApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static com.nexusphere.e2e.support.SovereigntyApi.unique;
import static org.assertj.core.api.Assertions.assertThat;

class SovereignNetworkBootstrapE2ETest extends E2ETestBase {

    private SovereigntyApi sovereignty;

    @BeforeEach
    void setUp() {
        sovereignty = new SovereigntyApi(api());
    }

    @Test
    @DisplayName("E2E-SC06-01 two active networks each list only their own organizations")
    void twoNetworksListOnlyTheirOwnOrganizations() {
        String networkA = sovereignty.activeNetwork("Network A");
        String networkB = sovereignty.activeNetwork("Network B");
        String acme = sovereignty.organization(networkA, "Acme");
        String globex = sovereignty.organization(networkB, "Globex");

        assertThat(api().get("/api/v1/networks/" + networkA).json().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(ids(sovereignty.listOrganizations(networkA))).containsExactly(acme);
        assertThat(ids(sovereignty.listOrganizations(networkB))).containsExactly(globex);
    }

    @Test
    @DisplayName("E2E-SC06-02 a network with one organization and one with two are both valid, no hierarchy required")
    void organizationHierarchyIsNotForced() {
        String single = sovereignty.activeNetwork("Single");
        String pair = sovereignty.activeNetwork("Pair");

        sovereignty.organization(single, "Solo");
        sovereignty.organization(pair, "First");
        sovereignty.organization(pair, "Second");

        JsonNode singleList = sovereignty.listOrganizations(single).json();
        assertThat(singleList).hasSize(1);
        assertThat(singleList.get(0).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(singleList.get(0).path("networkId").asString()).isEqualTo(single);
        assertThat(sovereignty.listOrganizations(pair).json()).hasSize(2);
    }

    @Test
    @DisplayName("E2E-SC06-03 activating an active network or reusing an organization name in one network is 409")
    void conflictsAreRejected() {
        String network = sovereignty.activeNetwork("Conflicts");
        String other = sovereignty.activeNetwork("Other");
        sovereignty.organization(network, "Acme");

        ApiClient.Response activateAgain = sovereignty.transition(network, "activate");
        ApiClient.Response duplicate = sovereignty.registerOrganization(network, "ACME");

        assertThat(activateAgain.status()).isEqualTo(409);
        assertThat(activateAgain.json().path("code").asString()).isEqualTo("NETWORK_ALREADY_ACTIVE");
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.json().path("code").asString()).isEqualTo("ORGANIZATION_NAME_TAKEN");
        assertThat(sovereignty.registerOrganization(other, "Acme").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("E2E-SC06-04 a suspended network blocks new operations but still answers reads")
    void suspendedNetworkBlocksChanges() {
        String network = sovereignty.activeNetwork("Suspended");
        String acme = sovereignty.organization(network, "Acme");

        assertThat(sovereignty.transition(network, "suspend").json().path("status").asString()).isEqualTo("SUSPENDED");

        ApiClient.Response register = sovereignty.registerOrganization(network, "Initech");
        assertThat(register.status()).isEqualTo(409);
        assertThat(register.json().path("code").asString()).isEqualTo("NETWORK_NOT_ACTIVE");
        assertThat(sovereignty.renameOrganization(network, acme, "Acme 2").status()).isEqualTo(409);
        assertThat(sovereignty.deactivateOrganization(network, acme).status()).isEqualTo(409);

        assertThat(api().get("/api/v1/networks/" + network).status()).isEqualTo(200);
        assertThat(ids(sovereignty.listOrganizations(network))).containsExactly(acme);
        assertThat(sovereignty.getOrganization(network, acme).json().path("name").asString()).isEqualTo("Acme");

        sovereignty.transition(network, "activate");
        assertThat(sovereignty.registerOrganization(network, "Initech").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("E2E-SC06-05 a new network is PENDING and takes no organizations until it is activated")
    void pendingNetworkTakesNoOrganizations() {
        ApiClient.Response created = sovereignty.createNetwork(unique("Pending"));
        String network = created.json().path("id").asString();

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("Location")).contains("/api/v1/networks/" + network);
        assertThat(created.json().path("status").asString()).isEqualTo("PENDING");
        assertThat(sovereignty.registerOrganization(network, "Acme").json().path("code").asString())
                .isEqualTo("NETWORK_NOT_ACTIVE");
    }

    @Test
    @DisplayName("E2E-SC06-06 an archived network cannot be activated or suspended again")
    void archivedNetworkIsTerminal() {
        String network = sovereignty.activeNetwork("Archived");

        assertThat(sovereignty.transition(network, "archive").json().path("status").asString()).isEqualTo("ARCHIVED");

        for (String action : List.of("activate", "suspend", "archive")) {
            ApiClient.Response response = sovereignty.transition(network, action);
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.json().path("code").asString()).isEqualTo("INVALID_NETWORK_TRANSITION");
        }
    }

    @Test
    @DisplayName("E2E-SC06-07 network names are unique, required and validated")
    void networkNamesAreValidated() {
        String name = unique("Duplicate");
        sovereignty.createNetwork(name);

        ApiClient.Response duplicate = sovereignty.createNetwork(name.toUpperCase());
        ApiClient.Response blank = sovereignty.createNetwork(" ");
        ApiClient.Response unknown = api().get("/api/v1/networks/00000000-0000-0000-0000-000000000000");
        ApiClient.Response malformed = api().get("/api/v1/networks/not-a-uuid");

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.json().path("code").asString()).isEqualTo("NETWORK_NAME_TAKEN");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.json().path("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.json().path("code").asString()).isEqualTo("INVALID_IDENTIFIER");
    }

    @Test
    @DisplayName("E2E-SC06-08 an organization can be renamed and deactivated, and then no longer changes")
    void organizationLifecycle() {
        String network = sovereignty.activeNetwork("Lifecycle");
        String acme = sovereignty.organization(network, "Acme");

        assertThat(sovereignty.renameOrganization(network, acme, "Acme Labs").json().path("name").asString())
                .isEqualTo("Acme Labs");
        assertThat(sovereignty.deactivateOrganization(network, acme).json().path("status").asString())
                .isEqualTo("DEACTIVATED");

        ApiClient.Response rename = sovereignty.renameOrganization(network, acme, "Acme Again");
        assertThat(rename.status()).isEqualTo(409);
        assertThat(rename.json().path("code").asString()).isEqualTo("ORGANIZATION_NOT_ACTIVE");
    }

    static List<String> ids(ApiClient.Response response) {
        assertThat(response.status()).isEqualTo(200);
        return response.json().valueStream().map(node -> node.path("id").asString()).toList();
    }
}
