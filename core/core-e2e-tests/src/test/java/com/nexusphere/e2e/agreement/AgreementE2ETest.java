package com.nexusphere.e2e.agreement;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.CapabilityApi;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.TrustedInteraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;

import static com.nexusphere.e2e.support.CapabilityApi.body;
import static com.nexusphere.e2e.support.CapabilityApi.published;
import static com.nexusphere.e2e.support.CapabilityApi.withdraw;
import static com.nexusphere.e2e.support.DelegationApi.assign;
import static com.nexusphere.e2e.support.DelegationApi.grant;
import static com.nexusphere.e2e.support.DelegationApi.transition;
import static com.nexusphere.e2e.support.TrustedInteraction.agreements;
import static org.assertj.core.api.Assertions.assertThat;

class AgreementE2ETest extends E2ETestBase {

    private TrustedInteraction world;

    @BeforeEach
    void establish() {
        world = TrustedInteraction.establish(api());
    }

    private String inA(String agreement) {
        return agreements(world.networkA) + "/" + agreement;
    }

    private String inB(String agreement) {
        return agreements(world.networkB) + "/" + agreement;
    }

    private static void assertError(ApiClient.Response response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.json().path("code").asString()).isEqualTo(code);
    }

    @Test
    @DisplayName("E2E-SC13-01 an agreement goes DRAFT, PROPOSED, ACCEPTED, ACTIVE and COMPLETED")
    void happyPath() {
        ApiClient.Response draft = world.draft(world.agentA, world.capability, "{\"quantity\":100}");
        assertThat(draft.status()).as(draft.body()).isEqualTo(201);
        assertThat(draft.json().path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.json().path("counterparty").path("organizationId").asString()).isEqualTo(world.organizationB);
        String agreement = draft.json().path("id").asString();

        assertThat(world.agentA.post(inA(agreement) + "/propose", "").json().path("status").asString())
                .isEqualTo("PROPOSED");
        assertThat(world.humanB.post(inB(agreement) + "/accept", "{\"version\":1}").json().path("status")
                .asString()).isEqualTo("ACCEPTED");
        assertThat(world.humanB.post(inB(agreement) + "/activate", "").json().path("status").asString())
                .isEqualTo("ACTIVE");
        JsonNode completed = world.humanB.post(inB(agreement) + "/complete", "").json();
        assertThat(completed.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.path("closedAt").asString()).isNotBlank();
        assertThat(world.humanA.get(inA(agreement)).json().path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("E2E-SC13-02 revising creates version 2, version 1 stays readable and accepting it is AGREEMENT_VERSION_SUPERSEDED")
    void revisionSupersedesVersionOne() {
        String agreement = world.proposed();

        JsonNode revised = world.humanB.post(inB(agreement) + "/revisions",
                "{\"expectedVersion\":1,\"terms\":{\"quantity\":80,\"material\":\"steel\",\"price\":12}}").json();
        assertThat(revised.path("currentVersion").asInt()).isEqualTo(2);
        assertThat(revised.path("status").asString()).isEqualTo("PROPOSED");
        JsonNode first = world.humanA.get(inA(agreement) + "/versions/1").json();
        assertThat(first.path("terms").path("quantity").asInt()).isEqualTo(100);
        assertThat(first.path("superseded").asBoolean()).isTrue();

        assertError(world.humanA.post(inA(agreement) + "/accept", "{\"version\":1}"), 409,
                "AGREEMENT_VERSION_SUPERSEDED");
        assertError(world.humanB.post(inB(agreement) + "/accept", "{\"version\":2}"), 403,
                "AGREEMENT_SELF_ACCEPTANCE");
        assertThat(world.humanA.post(inA(agreement) + "/accept", "{\"version\":2}").json().path("status").asString())
                .isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("E2E-SC13-03 DRAFT to ACTIVE directly is AGREEMENT_INVALID_TRANSITION")
    void invalidTransition() {
        String agreement = world.draft(world.agentA, world.capability, "{}").json().path("id").asString();

        assertError(world.humanA.post(inA(agreement) + "/activate", ""), 409, "AGREEMENT_INVALID_TRANSITION");
        assertError(world.humanB.post(inB(agreement) + "/accept", "{\"version\":1}"), 409,
                "AGREEMENT_INVALID_TRANSITION");
    }

    @Test
    @DisplayName("E2E-SC13-04 a principal of an organization that is not a party cannot accept, revise or terminate")
    void outsidersCannotChangeAgreement() {
        String agreement = world.proposed();
        String outsiderOrganization = world.sovereignty.organization(world.networkA, "Organization A2");
        String outsiderId = world.identities.human("Outsider");
        String outsiderPrincipal = world.identities.member(world.networkA, outsiderId, outsiderOrganization);
        assign(world.adminA, world.networkA, outsiderPrincipal, "AGREEMENT_MANAGER");
        ApiClient outsider = world.identities.as(world.identities.actor(outsiderId), world.networkA);

        assertError(outsider.post(inA(agreement) + "/accept", "{\"version\":1}"), 403, "AGREEMENT_PARTY_REQUIRED");
        assertError(outsider.post(inA(agreement) + "/revisions", "{\"expectedVersion\":1,\"terms\":{}}"), 403,
                "AGREEMENT_PARTY_REQUIRED");
        assertError(outsider.post(inA(agreement) + "/terminate", ""), 403, "AGREEMENT_PARTY_REQUIRED");
        assertThat(outsider.get(inA(agreement)).status()).isEqualTo(404);
        String networkC = world.sovereignty.activeNetwork("Network C");
        String humanC = world.identities.human("Human C");
        world.identities.member(networkC, humanC);
        ApiClient c = world.identities.as(world.identities.actor(humanC), networkC);
        assertThat(c.get(agreements(networkC) + "/" + agreement).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("E2E-SC13-05 each version shows proposer, accountable party, delegation, time, changes and acceptor")
    void versionsCarryAccountability() {
        String agreement = world.proposed();
        world.humanB.post(inB(agreement) + "/revisions",
                "{\"expectedVersion\":1,\"terms\":{\"quantity\":80,\"material\":\"steel\"}}");
        world.humanA.post(inA(agreement) + "/accept", "{\"version\":2}");

        JsonNode versions = world.humanA.get(inA(agreement) + "/versions").json();
        JsonNode first = versions.get(0);
        assertThat(first.path("proposedBy").asString()).isEqualTo(world.agentAPrincipal);
        assertThat(first.path("proposedByIdentity").asString()).isEqualTo(world.agentAIdentity);
        assertThat(first.path("onBehalfOf").path("organizationId").asString()).isEqualTo(world.organizationA);
        assertThat(first.path("delegationId").asString()).isEqualTo(world.delegation);
        assertThat(first.path("federationId").asString()).isEqualTo(world.federation);
        assertThat(first.path("decisionId").asString()).isNotBlank();
        assertThat(first.path("proposedAt").asString()).isNotBlank();
        assertThat(first.path("changes").valueStream().map(JsonNode::asString))
                .containsExactly("material", "quantity");
        JsonNode second = versions.get(1);
        assertThat(second.path("proposedBy").asString()).isEqualTo(world.humanBPrincipal);
        assertThat(second.path("onBehalfOf").path("organizationId").asString()).isEqualTo(world.organizationB);
        assertThat(second.path("delegationId").isNull()).isTrue();
        assertThat(second.path("changes").valueStream().map(JsonNode::asString)).containsExactly("quantity");
        assertThat(second.path("acceptedBy").asString()).isEqualTo(world.humanAPrincipal);
        assertThat(second.path("acceptedAt").asString()).isNotBlank();
    }

    @Test
    @DisplayName("E2E-SC13-06 two revisions with the same expected version: one succeeds and one is 409")
    void concurrentRevisions() {
        String agreement = world.proposed();

        ApiClient.Response first = world.humanB.post(inB(agreement) + "/revisions",
                "{\"expectedVersion\":1,\"terms\":{\"quantity\":90}}");
        ApiClient.Response second = world.agentA.post(inA(agreement) + "/revisions",
                "{\"expectedVersion\":1,\"terms\":{\"quantity\":110}}");

        assertThat(first.status()).isEqualTo(200);
        assertError(second, 409, "AGREEMENT_VERSION_CONFLICT");
        assertThat(world.humanA.get(inA(agreement)).json().path("currentVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("E2E-SC11-03 an agent may propose only within its delegated capability")
    void agentProposesOnlyWithinDelegation() {
        CapabilityApi capabilityApi = new CapabilityApi(world.api);
        String logistics = capabilityApi.type(CapabilityApi.uniqueCode("logistics.transport"), CapabilityApi.CNC_SCHEMA);
        String transport = published(world.humanB, world.networkB, body("Transport", logistics, null, null,
                "FEDERATED"));

        assertError(world.draft(world.agentA, transport, "{}"), 403, "DELEGATION_CONSTRAINT_VIOLATION");
        assertThat(world.draft(world.humanA, transport, "{}").status()).isEqualTo(201);
        assertError(world.agentA.post(inA(world.proposed()) + "/terminate", ""), 403, "DELEGATION_SCOPE_VIOLATION");
    }

    @Test
    @DisplayName("E2E-SC08-04 a withdrawn capability cannot be used in a new agreement")
    void withdrawnCapabilityCannotBeUsed() {
        assertThat(withdraw(world.humanB, world.networkB, world.capability).status()).isEqualTo(200);

        assertError(world.draft(world.agentA, world.capability, "{}"), 404, "NOT_FOUND");
    }

    @Test
    @DisplayName("E2E-SC05-01 after revocation the agent's next proposal is 403 DELEGATION_REVOKED")
    void revokedDelegationBlocksProposal() {
        assertThat(world.draft(world.agentA, world.capability, "{}").status()).isEqualTo(201);

        transition(world.humanA, world.networkA, world.delegation, "revoke");

        ApiClient.Response denied = world.draft(world.agentA, world.capability, "{}");
        assertError(denied, 403, "DELEGATION_REVOKED");
        assertThat(denied.json().path("correlationId").asString()).isNotBlank();
    }

    @Test
    @DisplayName("E2E-SC05-03 after the delegation expires the agent's proposal is 403 DELEGATION_EXPIRED")
    void expiredDelegationBlocksProposal() throws InterruptedException {
        transition(world.humanA, world.networkA, world.delegation, "revoke");
        Instant until = Instant.now().plus(Duration.ofSeconds(2));
        ApiClient.Response expiring = grant(world.humanA, world.networkA, world.agentAPrincipal,
                "\"agreement:propose\"", null, until.toString());
        assertThat(expiring.status()).isEqualTo(201);
        assertThat(world.draft(world.agentA, world.capability, "{}").status()).isEqualTo(201);
        while (Instant.now().isBefore(until)) {
            Thread.sleep(200);
        }

        assertError(world.draft(world.agentA, world.capability, "{}"), 403, "DELEGATION_EXPIRED");
    }
}
