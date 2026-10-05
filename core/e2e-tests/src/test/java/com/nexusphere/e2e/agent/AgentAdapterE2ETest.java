package com.nexusphere.e2e.agent;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.TrustedInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static com.nexusphere.e2e.support.TrustedInteraction.agreements;
import static com.nexusphere.e2e.support.TrustedInteraction.transactions;
import static org.assertj.core.api.Assertions.assertThat;

class AgentAdapterE2ETest extends E2ETestBase {

    private static String rpc(String networkId) {
        return "/api/v1/networks/" + networkId + "/agent/rpc";
    }

    private static JsonNode call(ApiClient as, String networkId, String method, String params) {
        ApiClient.Response response = as.post(rpc(networkId), "{\"jsonrpc\":\"2.0\",\"id\":\"" + method
                + "\",\"method\":\"" + method + "\",\"params\":" + params + "}");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.path("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(body.path("id").asString()).isEqualTo(method);
        return body;
    }

    private static JsonNode result(ApiClient as, String networkId, String method, String params) {
        JsonNode body = call(as, networkId, method, params);
        assertThat(body.has("error")).as(body.toString()).isFalse();
        return body.path("result");
    }

    @Test
    @DisplayName("E2E-SC17-01 a simulated external agent authenticates with its own credential and completes SC-01 steps 10 to 15")
    void externalAgentCompletesTheMainFlow() {
        TrustedInteraction world = TrustedInteraction.establish(api());

        JsonNode card = world.agentA.get("/api/v1/networks/" + world.networkA + "/agent/card").json();
        assertThat(card.path("principal").path("principalId").asString()).isEqualTo(world.agentAPrincipal);
        assertThat(card.path("principal").path("identityId").asString()).isEqualTo(world.agentAIdentity);
        assertThat(card.path("principal").path("identityType").asString()).isEqualTo("AGENT");
        assertThat(card.path("methods").valueStream().map(JsonNode::asString)).contains("capabilities/discover",
                "agreements/propose", "transactions/request");

        JsonNode found = result(world.agentA, world.networkA, "capabilities/discover",
                "{\"typeCode\":\"" + world.cnc + "\"}");
        assertThat(found.valueStream().map(capability -> capability.path("id").asString()))
                .containsExactly(world.capability);
        assertThat(found.get(0).path("organizationId").asString()).isEqualTo(world.organizationB);
        assertThat(found.get(0).path("federationId").asString()).isEqualTo(world.federation);

        JsonNode agreement = result(world.agentA, world.networkA, "agreements/propose", "{\"capabilityId\":\""
                + world.capability + "\",\"title\":\"CNC parts\",\"terms\":{\"quantity\":100,\"material\":\"steel\"}}");
        String agreementId = agreement.path("id").asString();
        assertThat(agreement.path("status").asString()).isEqualTo("PROPOSED");
        assertThat(agreement.path("version").asInt()).isEqualTo(1);
        assertThat(agreement.path("proposerOrganizationId").asString()).isEqualTo(world.organizationA);

        assertThat(world.humanB.post(agreements(world.networkB) + "/" + agreementId + "/accept", "{\"version\":1}")
                .status()).isEqualTo(200);
        assertThat(world.humanB.post(agreements(world.networkB) + "/" + agreementId + "/activate", "").status())
                .isEqualTo(200);
        assertThat(result(world.agentA, world.networkA, "agreements/get", "{\"agreementId\":\"" + agreementId + "\"}")
                .path("status").asString()).isEqualTo("ACTIVE");

        JsonNode transaction = result(world.agentA, world.networkA, "transactions/request",
                "{\"agreementId\":\"" + agreementId + "\",\"type\":\"capability.invocation\"}");
        String transactionId = transaction.path("id").asString();
        assertThat(transaction.path("status").asString()).isEqualTo("AUTHORIZED");
        assertThat(transaction.path("initiatingPrincipalId").asString()).isEqualTo(world.agentAPrincipal);
        assertThat(transaction.path("delegationId").asString()).isEqualTo(world.delegation);
        assertThat(transaction.path("federationId").asString()).isEqualTo(world.federation);

        assertThat(world.humanB.post(transactions(world.networkB) + "/" + transactionId + "/execute", "").status())
                .isEqualTo(200);
        assertThat(world.humanB.post(transactions(world.networkB) + "/" + transactionId + "/complete",
                "{\"result\":{\"delivered\":100}}").status()).isEqualTo(200);
        assertThat(result(world.agentA, world.networkA, "transactions/get",
                "{\"transactionId\":\"" + transactionId + "\"}").path("status").asString()).isEqualTo("COMPLETED");

        JsonNode trail = world.adminA.get("/api/v1/audit-events/trail?transactionId=" + transactionId).json();
        assertThat(trail.path("chain").valueStream().map(link -> link.path("kind").asString()))
                .containsExactly("DELEGATOR", "DELEGATION", "ACTOR", "FEDERATION", "CAPABILITY", "AGREEMENT",
                        "TRANSACTION");
        assertThat(trail.path("events").valueStream()
                .filter(event -> event.path("action").asString().equals("agreement:propose")
                        || event.path("action").asString().equals("transaction:initiate"))
                .map(event -> event.path("principalId").asString() + " " + event.path("result").asString()))
                .isNotEmpty().containsOnly(world.agentAPrincipal + " ALLOWED");
    }

    @Test
    @DisplayName("E2E-SC17-02 an out-of-scope request through the adapter is denied by the core and audited")
    void adapterRequestsAreAuthorizedByTheCore() {
        TrustedInteraction world = TrustedInteraction.establish(api(), "\"agreement:propose\"");
        String agreement = world.activeAgreement();

        JsonNode denied = call(world.agentA, world.networkA, "transactions/request",
                "{\"agreementId\":\"" + agreement + "\",\"type\":\"capability.invocation\"}");

        assertThat(denied.has("result")).isFalse();
        assertThat(denied.path("error").path("code").asInt()).isEqualTo(-32003);
        assertThat(denied.path("error").path("data").path("code").asString())
                .isEqualTo("DELEGATION_SCOPE_VIOLATION");
        assertThat(denied.path("error").path("data").path("category").asString()).isEqualTo("AUTHORIZATION_ERROR");
        JsonNode audit = world.adminA.get("/api/v1/audit-events?principalId=" + world.agentAPrincipal
                + "&result=DENIED").json();
        assertThat(audit.valueStream().map(event -> event.path("action").asString() + " "
                + event.path("reason").asString())).contains("transaction:initiate DELEGATION_SCOPE_VIOLATION");
        assertThat(world.agentA.get(transactions(world.networkA)).json().size()).isZero();

        JsonNode missing = call(world.agentA, world.networkA, "agreements/get",
                "{\"agreementId\":\"00000000-0000-0000-0000-000000000000\"}");
        assertThat(missing.path("error").path("code").asInt()).isEqualTo(-32004);
    }

    @Test
    @DisplayName("E2E-SC17-03 the adapter answers protocol errors in JSON-RPC and still needs the agent's own credential")
    void protocolErrorsAndAuthentication() {
        TrustedInteraction world = TrustedInteraction.establish(api());

        assertThat(world.agentA.post(rpc(world.networkA), "{\"jsonrpc\":").json().path("error").path("code").asInt())
                .isEqualTo(-32700);
        assertThat(world.agentA.post(rpc(world.networkA), "{\"id\":1,\"method\":\"capabilities/discover\"}").json()
                .path("error").path("code").asInt()).isEqualTo(-32600);
        JsonNode unknown = call(world.agentA, world.networkA, "robots/move", "{}");
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32601);
        JsonNode badParams = call(world.agentA, world.networkA, "transactions/get", "{\"transactionId\":\"x\"}");
        assertThat(badParams.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(badParams.path("error").path("data").path("parameter").asString()).isEqualTo("transactionId");

        JsonNode batch = world.agentA.post(rpc(world.networkA), "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":"
                + "\"capabilities/discover\",\"params\":{\"typeCode\":\"" + world.cnc + "\"}},{\"jsonrpc\":\"2.0\","
                + "\"id\":2,\"method\":\"unknown\"}]").json();
        assertThat(batch.size()).isEqualTo(2);
        assertThat(batch.get(0).path("result").size()).isEqualTo(1);
        assertThat(batch.get(1).path("error").path("code").asInt()).isEqualTo(-32601);

        String discover = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"capabilities/discover\",\"params\":{}}";
        assertThat(api().post(rpc(world.networkA), discover).status()).isEqualTo(401);
        assertThat(world.agentA.post(rpc(world.networkB), discover).status()).isEqualTo(403);
    }
}
