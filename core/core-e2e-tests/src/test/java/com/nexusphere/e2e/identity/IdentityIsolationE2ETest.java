package com.nexusphere.e2e.identity;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.SovereigntyApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityIsolationE2ETest extends E2ETestBase {

    @Test
    @DisplayName("E2E-SC15-01 network A cannot read network B identities by list or by ID")
    void identitiesOfAnotherNetworkAreInvisible() {
        SovereigntyApi sovereignty = new SovereigntyApi(api());
        IdentityApi identities = new IdentityApi(api());
        String networkA = sovereignty.activeNetwork("Identity A");
        String networkB = sovereignty.activeNetwork("Identity B");
        String alice = identities.human("Alice");
        String bob = identities.human("Bob");
        identities.member(networkA, alice);
        identities.member(networkB, bob);
        IdentityApi.Actor actor = identities.actor(alice);

        ApiClient.Response list = identities.as(actor).get("/api/v1/networks/" + networkA + "/identities");
        ApiClient.Response byId = identities.as(actor).get("/api/v1/networks/" + networkA + "/identities/" + bob);
        ApiClient.Response foreignList = identities.as(actor).get("/api/v1/networks/" + networkB + "/identities");

        assertThat(list.json().valueStream().map(member -> member.path("id").asString())).containsExactly(alice);
        assertThat(byId.status()).isEqualTo(404);
        assertThat(byId.body()).doesNotContain("Bob");
        assertThat(foreignList.status()).isEqualTo(403);
        assertThat(foreignList.json().path("code").asString()).isEqualTo("NETWORK_ACCESS_DENIED");
    }

    @Test
    @DisplayName("E2E-SC15-10 a valid token of network A with the network context of B is 403")
    void networkContextSpoofingIsForbidden() {
        SovereigntyApi sovereignty = new SovereigntyApi(api());
        IdentityApi identities = new IdentityApi(api());
        String networkA = sovereignty.activeNetwork("Spoof A");
        String networkB = sovereignty.activeNetwork("Spoof B");
        String alice = identities.human("Alice");
        identities.member(networkA, alice);
        IdentityApi.Actor actor = identities.actor(alice);

        ApiClient.Response headerSpoof = identities.as(actor, networkB).get("/api/v1/principal");
        ApiClient.Response mismatch = identities.as(actor, networkA).get("/api/v1/networks/" + networkB + "/identities");

        assertThat(headerSpoof.status()).isEqualTo(403);
        assertThat(headerSpoof.json().path("code").asString()).isEqualTo("NETWORK_ACCESS_DENIED");
        assertThat(mismatch.status()).isEqualTo(403);
        assertThat(mismatch.json().path("code").asString()).isEqualTo("NETWORK_CONTEXT_MISMATCH");
    }
}
