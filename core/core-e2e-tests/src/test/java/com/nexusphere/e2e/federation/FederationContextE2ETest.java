package com.nexusphere.e2e.federation;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.FederationApi;
import com.nexusphere.e2e.support.TrustedInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.nexusphere.e2e.support.CapabilityApi.body;
import static com.nexusphere.e2e.support.CapabilityApi.published;
import static com.nexusphere.e2e.support.FederationApi.federations;
import static com.nexusphere.e2e.support.FederationApi.trustNetwork;
import static org.assertj.core.api.Assertions.assertThat;

class FederationContextE2ETest extends E2ETestBase {

    @Test
    @DisplayName("E2E-SC15-04 a federation the caller's network is not part of cannot be read, used or acted on")
    void foreignFederationsGiveNoContext() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String networkC = world.sovereignty.activeNetwork("Network C");
        String organizationC = world.sovereignty.organization(networkC, "Organization C");
        String adminCIdentity = world.identities.human("Admin C");
        world.identities.administrator(networkC, adminCIdentity);
        ApiClient adminC = world.identities.as(world.identities.actor(adminCIdentity), networkC);
        String humanC = world.identities.human("Human C");
        world.identities.member(networkC, humanC, organizationC);
        String capabilityC = published(world.identities.as(world.identities.actor(humanC), networkC), networkC,
                body("CNC of C", world.cnc, null, null, "FEDERATED"));
        trustNetwork(world.adminB, world.networkB, networkC, TrustedInteraction.SCOPES, null);
        trustNetwork(adminC, networkC, world.networkB, TrustedInteraction.SCOPES, null);
        String federationBC = FederationApi.active(world.adminB, world.networkB, adminC, networkC);

        ApiClient.Response read = world.adminA.get(federations(world.networkA) + "/" + federationBC);
        assertThat(read.status()).isEqualTo(404);
        assertThat(FederationApi.transition(world.adminA, world.networkA, federationBC, "suspend").status())
                .isEqualTo(404);
        assertThat(world.adminA.get(federations(world.networkA)).json().valueStream()
                .map(federation -> federation.path("id").asString())).contains(world.federation)
                .doesNotContain(federationBC);
        ApiClient.Response spoofed = world.adminA.get(federations(world.networkB) + "/" + federationBC);
        assertThat(spoofed.status()).isEqualTo(403);
        assertThat(spoofed.json().path("code").asString()).isEqualTo("NETWORK_CONTEXT_MISMATCH");

        ApiClient.Response discovered = world.agentA.get("/api/v1/networks/" + world.networkA
                + "/discovery/capabilities?typeCode=" + world.cnc);
        assertThat(discovered.json().valueStream().map(capability -> capability.path("id").asString()))
                .contains(world.capability).doesNotContain(capabilityC);
        assertThat(world.agentA.get("/api/v1/networks/" + world.networkA + "/discovery/capabilities/" + capabilityC)
                .status()).isEqualTo(404);
        assertThat(world.draft(world.agentA, capabilityC, "{\"quantity\":1}").status()).isEqualTo(404);
        assertThat(FederationApi.transition(adminC, networkC, federationBC, "suspend").status()).isEqualTo(200);
    }
}
