package com.nexusphere.ledger.e2e.policy;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.FakeApprover;
import com.nexusphere.ledger.e2e.support.FakeOidcProvider;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalE2ETest {

    private PostgreSQLContainer postgres;
    private FakeApprover approver;
    private final KeyPair keys = SigningKeys.generate();

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        approver = FakeApprover.start();
    }

    @AfterEach
    void stop() {
        approver.close();
        postgres.stop();
    }

    private static JsonNode decide(LedgerClient client, String target) {
        return client.post("/api/v1/decisions", LedgerClient.json(Map.of("principalId", "alice",
                "action", "tools/call", "target", target))).json();
    }

    private static String grantBody() {
        return LedgerClient.json(Map.of("principalId", "alice", "agentId", "payments-agent",
                "actions", List.of("tools/call"), "targets", List.of("bank/*"),
                "expiresAt", Instant.now().plus(1, ChronoUnit.HOURS).toString()));
    }

    private static LedgerClient agent(LedgerClient operator) {
        JsonNode agent = operator.post("/api/v1/agents", LedgerClient.json(Map.of("agentId", "payments-agent",
                "name", "Payments agent", "ownerId", "acme"))).json();
        return operator.withApiKey(agent.path("apiKey").asString());
    }

    private static void waitUntil(BooleanSupplier condition) {
        long until = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static JsonNode waitFor(Supplier<JsonNode> read, String field, String value) {
        waitUntil(() -> value.equals(read.get().path(field).asString()));
        return read.get();
    }

    @Test
    void theOperatorApprovesOrDeniesOnceAndSilenceDenies() {
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, freePort(), keys,
                "--ledger.approval.timeout=4s", "--ledger.approval.poll-interval=500ms",
                "--ledger.approval.webhook-url=" + approver.url("/webhook"))) {
            LedgerClient operator = ledger.client();
            LedgerClient agent = agent(operator);
            assertThat(operator.post("/api/v1/grants", grantBody()).status()).isEqualTo(201);

            LedgerClient.Response bogus = operator.put("/api/v1/policies", LedgerClient.json(Map.of("text",
                    "@approval(\"anyone\") forbid(principal, action, resource);")));
            assertThat(bogus.json().path("details").path("fields").path("text").asString())
                    .isEqualTo("The approval of policy policy0 must be principal, operator or any");
            LedgerClient.Response permit = operator.put("/api/v1/policies", LedgerClient.json(Map.of("text",
                    "@id(\"p\") @approval(\"any\") permit(principal, action, resource);")));
            assertThat(permit.json().path("details").path("fields").path("text").asString())
                    .isEqualTo("Only a forbid policy can ask for approval, not p");
            operator.put("/api/v1/policies", LedgerClient.json(Map.of("text", """
                    @id("wire-needs-operator")
                    @approval("operator")
                    forbid(principal, action, resource == Target::"bank/wire_transfer");

                    @id("no-loans")
                    forbid(principal, action, resource == Target::"bank/loan");
                    """)));

            assertThat(decide(agent, "bank/loan").path("reasonCode").asString()).isEqualTo("POLICY_DENIED");
            JsonNode first = decide(agent, "bank/wire_transfer");
            String approvalId = first.path("approvalId").asString();
            assertThat(first.path("decision").asString()).isEqualTo("DENY");
            assertThat(first.path("reasonCode").asString()).isEqualTo("APPROVAL_PENDING");
            assertThat(first.path("reason").asString()).startsWith("Policy wire-needs-operator needs the operator "
                    + "to approve tools/call on bank/wire_transfer; approval " + approvalId + " waits until ");
            assertThat(decide(agent, "bank/wire_transfer").path("approvalId").asString()).isEqualTo(approvalId);
            JsonNode pendingEvidence = operator.get("/api/v1/evidence/" + first.path("decisionId").asString()).json();
            assertThat(pendingEvidence.path("attributes").path("approval.id").asString()).isEqualTo(approvalId);
            assertThat(pendingEvidence.path("attributes").has("approval.expiresAt")).isTrue();

            waitUntil(() -> !approver.webhooks().isEmpty());
            JsonNode hook = approver.webhooks().getFirst().body();
            assertThat(hook.path("approvalId").asString()).isEqualTo(approvalId);
            assertThat(hook.path("type").asString()).isEqualTo("approval.requested");
            assertThat(hook.path("policies").get(0).asString()).isEqualTo("wire-needs-operator");
            assertThat(approver.webhooks().getFirst().headers().get("signature-input")).contains("nexusphere");
            assertThat(approver.webhooks()).hasSize(1);

            assertThat(agent.get("/api/v1/approvals/" + approvalId).json().path("status").asString())
                    .isEqualTo("PENDING");
            assertThat(operator.get("/api/v1/approvals?status=PENDING").json().path("items")).hasSize(1);
            JsonNode approved = operator.post("/api/v1/approvals/" + approvalId + "/approve",
                    LedgerClient.json(Map.of("reason", "Invoice 42 checked"))).json();
            assertThat(approved.path("status").asString()).isEqualTo("APPROVED");
            assertThat(approved.path("method").asString()).isEqualTo("operator");
            assertThat(operator.post("/api/v1/approvals/" + approvalId + "/deny").json().path("code").asString())
                    .isEqualTo("APPROVAL_RESOLVED");
            assertThat(agent.post("/api/v1/approvals/" + approvalId + "/approve").status()).isEqualTo(403);

            JsonNode allowed = decide(agent, "bank/wire_transfer");
            assertThat(allowed.path("decision").asString()).isEqualTo("ALLOW");
            assertThat(allowed.path("reasonCode").asString()).isEqualTo("ALLOWED_BY_APPROVAL");
            assertThat(allowed.path("approvalId").asString()).isEqualTo(approvalId);
            assertThat(operator.get("/api/v1/approvals/" + approvalId).json().path("used").asBoolean()).isTrue();

            JsonNode again = decide(agent, "bank/wire_transfer");
            String second = again.path("approvalId").asString();
            assertThat(second).isNotEqualTo(approvalId);
            operator.post("/api/v1/approvals/" + second + "/deny");
            assertThat(decide(agent, "bank/wire_transfer").path("reasonCode").asString())
                    .isEqualTo("APPROVAL_DENIED");

            String third = decide(agent, "bank/wire_transfer").path("approvalId").asString();
            JsonNode expired = waitFor(() -> operator.get("/api/v1/approvals/" + third).json(), "status", "EXPIRED");
            assertThat(expired.path("method").asString()).isEqualTo("timeout");
            assertThat(operator.post("/api/v1/approvals/" + third + "/approve").json().path("code").asString())
                    .isEqualTo("APPROVAL_RESOLVED");
            assertThat(decide(agent, "bank/wire_transfer").path("reasonCode").asString())
                    .isEqualTo("APPROVAL_EXPIRED");

            JsonNode expireEvidence = operator.get("/api/v1/evidence/" + expired.path("evidenceId").asString()).json();
            assertThat(expireEvidence.path("action").asString()).isEqualTo("approval/expire");
            assertThat(expireEvidence.path("attributes").path("approval.id").asString()).isEqualTo(third);
            JsonNode approveEvidence = operator.get("/api/v1/evidence/" + approved.path("evidenceId").asString())
                    .json();
            assertThat(approveEvidence.path("action").asString()).isEqualTo("approval/approve");
            assertThat(approveEvidence.path("reason").asString()).isEqualTo("Invoice 42 checked");
            assertThat(operator.get("/api/v1/verification").json().path("valid").asBoolean()).isTrue();
        }
    }

    @Test
    void thePrincipalAnswersInTheLedgerOrOnTheirDeviceThroughCiba() {
        try (FakeOidcProvider oidc = FakeOidcProvider.start();
             StandaloneLedger ledger = StandaloneLedger.start(postgres, freePort(), keys,
                     "--ledger.oidc.issuer=" + oidc.issuer(),
                     "--ledger.oidc.audience=" + FakeOidcProvider.AUDIENCE,
                     "--ledger.oidc.principal-claim=preferred_username",
                     "--ledger.approval.poll-interval=500ms",
                     "--ledger.approval.ciba.endpoint=" + approver.url("/bc-authorize"),
                     "--ledger.approval.ciba.token-endpoint=" + approver.url("/token"),
                     "--ledger.approval.ciba.client-id=nexusphere-ledger",
                     "--ledger.approval.ciba.client-secret=secret")) {
            LedgerClient operator = ledger.client();
            LedgerClient agent = agent(operator);
            LedgerClient alice = operator.withApiKey(oidc.token("alice"));
            LedgerClient bob = operator.withApiKey(oidc.token("bob"));
            assertThat(alice.post("/api/v1/principal/grants", grantBody()).status()).isEqualTo(201);
            operator.put("/api/v1/policies", LedgerClient.json(Map.of("text", """
                    @id("wire-needs-principal")
                    @approval("principal")
                    forbid(principal, action, resource == Target::"bank/wire_transfer");
                    """)));

            approver.answerNext("token:" + oidc.token("alice"));
            String viaDevice = decide(agent, "bank/wire_transfer").path("approvalId").asString();
            assertThat(operator.post("/api/v1/approvals/" + viaDevice + "/approve").status()).isEqualTo(403);
            JsonNode approved = waitFor(() -> operator.get("/api/v1/approvals/" + viaDevice).json(), "status",
                    "APPROVED");
            assertThat(approved.path("method").asString()).isEqualTo("ciba");
            FakeApprover.BackchannelRequest request = approver.requests().getFirst();
            assertThat(request.loginHint()).isEqualTo("alice");
            assertThat(request.bindingMessage()).isEqualTo("payments-agent tools/call on bank/wire_transfer");
            assertThat(request.authorization()).startsWith("Basic ");
            JsonNode consent = operator.get("/api/v1/evidence/" + approved.path("evidenceId").asString()).json();
            assertThat(consent.path("attributes").path("consent.subject").asString()).isEqualTo("subject-alice");
            assertThat(decide(agent, "bank/wire_transfer").path("reasonCode").asString())
                    .isEqualTo("ALLOWED_BY_APPROVAL");

            approver.answerNext("wait");
            String inLedger = decide(agent, "bank/wire_transfer").path("approvalId").asString();
            waitUntil(() -> approver.requests().size() == 2);
            assertThat(bob.get("/api/v1/principal/approvals/" + inLedger).status()).isEqualTo(404);
            assertThat(bob.post("/api/v1/principal/approvals/" + inLedger + "/approve").status()).isEqualTo(404);
            assertThat(alice.get("/api/v1/principal/approvals?status=PENDING").json().path("items")).hasSize(1);
            JsonNode denied = alice.post("/api/v1/principal/approvals/" + inLedger + "/deny",
                    LedgerClient.json(Map.of("reason", "Not this one"))).json();
            assertThat(denied.path("status").asString()).isEqualTo("DENIED");
            assertThat(denied.path("method").asString()).isEqualTo("principal");
            assertThat(decide(agent, "bank/wire_transfer").path("reasonCode").asString())
                    .isEqualTo("APPROVAL_DENIED");

            approver.answerNext("deny");
            String refused = decide(agent, "bank/wire_transfer").path("approvalId").asString();
            JsonNode refusedOnDevice = waitFor(() -> operator.get("/api/v1/approvals/" + refused).json(), "status",
                    "DENIED");
            assertThat(refusedOnDevice.path("method").asString()).isEqualTo("ciba");
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
