package com.nexusphere.e2e.sovereignty;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.SovereigntyApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkIsolationE2ETest extends E2ETestBase {

    @Test
    @DisplayName("E2E-SC15-02 organizations of network B are not found through network A")
    void organizationsOfAnotherNetworkAreNotFound() {
        SovereigntyApi sovereignty = new SovereigntyApi(api());
        String networkA = sovereignty.activeNetwork("Isolation A");
        String networkB = sovereignty.activeNetwork("Isolation B");
        String ownedByB = sovereignty.organization(networkB, "Hidden");

        ApiClient.Response read = sovereignty.getOrganization(networkA, ownedByB);
        ApiClient.Response rename = sovereignty.renameOrganization(networkA, ownedByB, "Taken over");
        ApiClient.Response deactivate = sovereignty.deactivateOrganization(networkA, ownedByB);

        assertThat(read.status()).isEqualTo(404);
        assertThat(read.json().path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(read.body()).doesNotContain("Hidden");
        assertThat(rename.status()).isEqualTo(404);
        assertThat(deactivate.status()).isEqualTo(404);
        assertThat(SovereignNetworkBootstrapE2ETest.ids(sovereignty.listOrganizations(networkA))).doesNotContain(ownedByB);
        assertThat(sovereignty.getOrganization(networkB, ownedByB).json().path("name").asString()).isEqualTo("Hidden");
    }
}
