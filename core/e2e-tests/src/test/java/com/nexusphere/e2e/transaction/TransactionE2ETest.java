package com.nexusphere.e2e.transaction;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.TrustedInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static com.nexusphere.e2e.support.CapabilityApi.body;
import static com.nexusphere.e2e.support.CapabilityApi.published;
import static com.nexusphere.e2e.support.FederationApi.transition;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionE2ETest extends E2ETestBase {

    static String transactions(String networkId) {
        return "/api/v1/networks/" + networkId + "/transactions";
    }

    static ApiClient.Response request(ApiClient as, String networkId, String agreement, String capability) {
        String covered = capability == null ? "" : ",\"capabilityId\":\"" + capability + "\"";
        return as.post(transactions(networkId), "{\"agreementId\":\"" + agreement + "\"" + covered
                + ",\"type\":\"capability.invocation\",\"metadata\":{\"quantity\":100}}");
    }

    private static void assertError(ApiClient.Response response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.json().path("code").asString()).isEqualTo(code);
    }

    @Test
    @DisplayName("E2E-SC14-01 a transaction goes REQUESTED, AUTHORIZED, EXECUTING and COMPLETED and references principal, delegation, federation, agreement and capability")
    void lifecycle() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String agreement = world.activeAgreement();

        ApiClient.Response requested = request(world.agentA, world.networkA, agreement, null);
        assertThat(requested.status()).as(requested.body()).isEqualTo(201);
        JsonNode transaction = requested.json();
        assertThat(transaction.path("status").asString()).isEqualTo("AUTHORIZED");
        assertThat(transaction.path("initiatingPrincipalId").asString()).isEqualTo(world.agentAPrincipal);
        assertThat(transaction.path("requesterOrganizationId").asString()).isEqualTo(world.organizationA);
        assertThat(transaction.path("delegationId").asString()).isEqualTo(world.delegation);
        assertThat(transaction.path("federationId").asString()).isEqualTo(world.federation);
        assertThat(transaction.path("agreementId").asString()).isEqualTo(agreement);
        assertThat(transaction.path("capabilityId").asString()).isEqualTo(world.capability);
        assertThat(transaction.path("decisionId").asString()).isNotBlank();
        String id = transaction.path("id").asString();

        ApiClient.Response executing = world.humanB.post(transactions(world.networkB) + "/" + id + "/execute", "");
        assertThat(executing.json().path("status").asString()).as(executing.body()).isEqualTo("EXECUTING");
        JsonNode completed = world.humanB.post(transactions(world.networkB) + "/" + id + "/complete",
                "{\"result\":{\"delivered\":100}}").json();
        assertThat(completed.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.path("result").path("delivered").asInt()).isEqualTo(100);
        assertThat(completed.path("executorPrincipalId").asString()).isEqualTo(world.humanBPrincipal);
        assertThat(world.agentA.get(transactions(world.networkA) + "/" + id).json().path("status").asString())
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("E2E-SC14-02 a transaction under an agreement that is not ACTIVE is REJECTED with a reason")
    void inactiveAgreementRejects() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String agreement = world.proposed();

        JsonNode rejected = request(world.agentA, world.networkA, agreement, null).json();

        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("reason").asString()).isEqualTo("AGREEMENT_NOT_ACTIVE");
        assertError(world.humanB.post(transactions(world.networkB) + "/" + rejected.path("id").asString()
                + "/execute", ""), 409, "TRANSACTION_INVALID_TRANSITION");
    }

    @Test
    @DisplayName("E2E-SC14-03 a transaction for a capability the agreement does not cover is REJECTED")
    void uncoveredCapabilityRejects() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String agreement = world.activeAgreement();
        String other = published(world.humanB, world.networkB, body("Other CNC", world.cnc, null, null, "FEDERATED"));

        JsonNode rejected = request(world.agentA, world.networkA, agreement, other).json();

        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("reason").asString()).isEqualTo("CAPABILITY_NOT_COVERED");
    }

    @Test
    @DisplayName("E2E-SC14-04 cancelling before execution is CANCELLED and completion by the requesting side is 403")
    void cancelAndCompletionRules() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String agreement = world.activeAgreement();
        String cancelled = request(world.agentA, world.networkA, agreement, null).json().path("id").asString();
        assertThat(world.humanA.post(transactions(world.networkA) + "/" + cancelled + "/cancel", "").json()
                .path("status").asString()).isEqualTo("CANCELLED");

        String running = request(world.agentA, world.networkA, agreement, null).json().path("id").asString();
        world.humanB.post(transactions(world.networkB) + "/" + running + "/execute", "");
        assertError(world.agentA.post(transactions(world.networkA) + "/" + running + "/complete", ""), 403,
                "TRANSACTION_PROVIDER_REQUIRED");
        assertError(world.humanA.post(transactions(world.networkA) + "/" + running + "/cancel", ""), 409,
                "TRANSACTION_INVALID_TRANSITION");
        assertError(request(world.humanB, world.networkB, agreement, null), 403, "TRANSACTION_REQUESTER_REQUIRED");
        JsonNode failed = world.humanB.post(transactions(world.networkB) + "/" + running + "/fail",
                "{\"reason\":\"spindle overheated\"}").json();
        assertThat(failed.path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("reason").asString()).isEqualTo("spindle overheated");
    }

    @Test
    @DisplayName("E2E-SC03-01 an agent delegated only agreement:propose is denied DELEGATION_SCOPE_VIOLATION and no transaction exists")
    void outOfScopeAgentActionIsDenied() {
        TrustedInteraction world = TrustedInteraction.establish(api(), "\"agreement:propose\"");
        String agreement = world.activeAgreement();

        ApiClient.Response denied = request(world.agentA, world.networkA, agreement, null);

        assertError(denied, 403, "DELEGATION_SCOPE_VIOLATION");
        assertThat(denied.json().path("correlationId").asString()).isNotBlank();
        assertThat(world.humanA.get(transactions(world.networkA)).json().size()).isZero();
        assertThat(world.humanB.get(transactions(world.networkB)).json().size()).isZero();
    }

    @Test
    @DisplayName("E2E-SC09-04 a suspended federation blocks transactions and resuming restores them")
    void suspensionBlocksTransactions() {
        TrustedInteraction world = TrustedInteraction.establish(api());
        String agreement = world.activeAgreement();

        transition(world.adminB, world.networkB, world.federation, "suspend");
        assertError(request(world.agentA, world.networkA, agreement, null), 403, "FEDERATION_REQUIRED");
        transition(world.adminB, world.networkB, world.federation, "resume");
        assertThat(request(world.agentA, world.networkA, agreement, null).json().path("status").asString())
                .isEqualTo("AUTHORIZED");
    }
}
