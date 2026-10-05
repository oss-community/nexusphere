package com.nexusphere.e2e.primary;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.TrustedInteraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static com.nexusphere.e2e.support.DelegationApi.evaluate;
import static com.nexusphere.e2e.support.TrustedInteraction.agreements;
import static com.nexusphere.e2e.support.TrustedInteraction.transactions;
import static org.assertj.core.api.Assertions.assertThat;

class PrimaryTrustedInteractionE2ETest extends E2ETestBase {

    private TrustedInteraction world;
    private String agreement;
    private String transaction;

    @BeforeEach
    void runMainFlow() {
        world = TrustedInteraction.establish(api());

        JsonNode directory = world.adminA.get("/api/v1/networks/" + world.networkA + "/discovery/networks").json();
        assertThat(directory.valueStream().map(network -> network.path("networkId").asString()))
                .contains(world.networkB);

        ApiClient.Response found = world.agentA.get("/api/v1/networks/" + world.networkA
                + "/discovery/capabilities?typeCode=" + world.cnc);
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.json().get(0).path("id").asString()).isEqualTo(world.capability);

        JsonNode decision = evaluate(world.agentA, "agreement:propose", world.networkB, world.cnc, null);
        assertThat(decision.path("result").asString()).isEqualTo("ALLOW");

        agreement = world.activeAgreement();
        transaction = world.completedTransaction(agreement);
    }

    @Test
    @DisplayName("E2E-SC01-01 the main flow ends with an ACTIVE federation, an ACTIVE agreement version 1 and a COMPLETED transaction")
    void mainFlowCompletes() {
        assertThat(world.adminA.get("/api/v1/networks/" + world.networkA + "/federations/" + world.federation)
                .json().path("status").asString()).isEqualTo("ACTIVE");
        JsonNode active = world.humanA.get(agreements(world.networkA) + "/" + agreement).json();
        assertThat(active.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(active.path("currentVersion").asInt()).isEqualTo(1);
        assertThat(world.humanA.get(transactions(world.networkA) + "/" + transaction).json().path("status")
                .asString()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("E2E-SC01-02 the agreement and the transaction show Agent A as acting principal and Organization A as accountable party")
    void actingPrincipalAndAccountableParty() {
        JsonNode version = world.humanA.get(agreements(world.networkA) + "/" + agreement + "/versions/1").json();
        assertThat(version.path("proposedBy").asString()).isEqualTo(world.agentAPrincipal);
        assertThat(version.path("onBehalfOf").path("organizationId").asString()).isEqualTo(world.organizationA);
        JsonNode completed = world.humanA.get(transactions(world.networkA) + "/" + transaction).json();
        assertThat(completed.path("initiatingPrincipalId").asString()).isEqualTo(world.agentAPrincipal);
        assertThat(completed.path("requesterOrganizationId").asString()).isEqualTo(world.organizationA);
    }

    @Test
    @DisplayName("E2E-SC01-03 every audit event of Agent A references an ALLOW decision with the created delegation and federation")
    void auditEventsReferenceDecisions() {
        JsonNode events = world.adminA.get("/api/v1/audit-events?principalId=" + world.agentAPrincipal).json();
        List<JsonNode> steps = events.valueStream()
                .filter(event -> List.of("agreement:created", "agreement:proposed", "transaction:requested",
                        "transaction:authorized").contains(event.path("action").asString()))
                .toList();
        assertThat(steps).extracting(event -> event.path("action").asString())
                .contains("agreement:created", "agreement:proposed", "transaction:requested", "transaction:authorized");
        for (JsonNode step : steps) {
            JsonNode decision = world.adminA.get("/api/v1/authorization/decisions/"
                    + step.path("decisionId").asString()).json();
            assertThat(decision.path("result").asString()).as(step.toString()).isEqualTo("ALLOW");
            assertThat(decision.path("delegationId").asString()).isEqualTo(world.delegation);
            assertThat(decision.path("federationId").asString()).isEqualTo(world.federation);
            assertThat(step.path("delegationId").asString()).isEqualTo(world.delegation);
            assertThat(step.path("federationId").asString()).isEqualTo(world.federation);
        }
    }

    @Test
    @DisplayName("E2E-SC01-04 the accountability trail is Human A, delegation, Agent A, federation, capability, agreement version 1 and transaction")
    void accountabilityTrail() {
        JsonNode trail = world.adminA.get("/api/v1/audit-events/trail?transactionId=" + transaction).json();

        JsonNode chain = trail.path("chain");
        assertThat(chain.valueStream().map(link -> link.path("kind").asString())).containsExactly("DELEGATOR",
                "DELEGATION", "ACTOR", "FEDERATION", "CAPABILITY", "AGREEMENT", "TRANSACTION");
        assertThat(chain.valueStream().map(link -> link.path("id").asString())).containsExactly(
                world.humanAPrincipal, world.delegation, world.agentAPrincipal, world.federation, world.capability,
                agreement, transaction);
        assertThat(chain.get(0).path("attributes").path("identityId").asString()).isEqualTo(world.humanAIdentity);
        assertThat(chain.get(2).path("attributes").path("onBehalfOf").asString()).isEqualTo(world.organizationA);
        assertThat(chain.get(5).path("attributes").path("version").asString()).isEqualTo("1");
    }

    @Test
    @DisplayName("E2E-SC01-05 neither network lists the other network's members")
    void membersStayInTheirNetwork() {
        List<String> membersOfB = world.humanB.get("/api/v1/networks/" + world.networkB + "/identities").json()
                .valueStream().map(member -> member.path("id").asString()).toList();
        List<String> membersOfA = world.humanA.get("/api/v1/networks/" + world.networkA + "/identities").json()
                .valueStream().map(member -> member.path("id").asString()).toList();

        assertThat(membersOfB).contains(world.humanBIdentity).doesNotContain(world.humanAIdentity, world.agentAIdentity);
        assertThat(membersOfA).contains(world.humanAIdentity, world.agentAIdentity).doesNotContain(world.humanBIdentity);
    }
}
