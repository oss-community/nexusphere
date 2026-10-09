package com.nexusphere.ledger.e2e.a2a;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.FakeA2aAgent;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.e2e.support.SupplierLedger;
import com.nexusphere.ledger.mandate.ExchangeReceipt;
import com.nexusphere.ledger.mandate.ExchangeRequest;
import com.nexusphere.ledger.mandate.Jws;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.Mandates;
import com.nexusphere.ledger.mandate.SdJwt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class A2aE2ETest extends LedgerE2ETestBase {

    private static final PrivateKey DEVELOPMENT_KEY =
            SigningKeys.decodePrivate("MC4CAQAwBQYDK2VwBCIEIDPXxsOX77t9k2XIr5aVh1AORIeW8w4rXooP+vMeOPKL");

    private static FakeA2aAgent sales;
    private static SupplierLedger supplier;

    @BeforeAll
    static void startSupplier() {
        sales = FakeA2aAgent.start();
        supplier = SupplierLedger.start(SUPPLIER_PORT, ISSUER, sales.url());
    }

    @AfterAll
    static void stopSupplier() {
        supplier.close();
    }

    private static String message(String method, String id) {
        return LedgerClient.json(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params",
                Map.of("message", Map.of("role", "user", "messageId", id, "parts",
                        List.of(Map.of("kind", "text", "text", "Order 40 pallets"))))));
    }

    private RegisteredAgent agentWithGrant(List<String> targets) {
        RegisteredAgent agent = registerAgent("acme");
        grant(grantBody("alice", agent.agentId(), List.of("a2a/send"), targets));
        return agent;
    }

    private static Map<String, String> principal() {
        return Map.of("X-Ledger-Principal", "alice");
    }

    private JsonNode outcomeOf(String agentId) {
        JsonNode items = ledger().get("/api/v1/evidence?agentId=" + agentId).json().path("items");
        return items.get(items.size() - 1);
    }

    @Test
    void anAllowedMessageCarriesAMandateAndBothLedgersHoldMatchingEvidence() {
        RegisteredAgent agent = agentWithGrant(List.of("supplier/*"));
        String body = message("message/send", "m-1");

        LedgerClient.Response response = agent.client().post("/a2a/out/supplier", body, principal());
        JsonNode sent = outcomeOf(agent.agentId());
        long peerSequence = Long.parseLong(sent.path("attributes").path("a2a.peer.sequence").asString());
        JsonNode received = supplier.client().get("/api/v1/evidence?agentId=sales&limit=500").json().path("items");
        JsonNode receivedEntry = null;
        for (JsonNode entry : received) {
            if (entry.path("sequence").asLong() == peerSequence) {
                receivedEntry = entry;
            }
        }
        String receipt = response.header("X-Nexusphere-Receipt").orElseThrow();
        ExchangeReceipt claims = ExchangeReceipt.fromPayload(Jws.parse(receipt).payload());
        JsonNode outbound = agent.client().get("/api/v1/a2a/exchanges/" + response.header("X-Ledger-Exchange")
                .orElseThrow()).json();

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("result").path("parts").get(0).path("text").asString())
                .isEqualTo("Order accepted");
        assertThat(response.header("X-Ledger-Receipt")).hasValue("VERIFIED");
        assertThat(sales.lastAuthorization()).isEqualTo(FakeA2aAgent.AUTHORIZATION);
        assertThat(new String(sales.lastBody(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(sent.path("action").asString()).isEqualTo("a2a/send");
        assertThat(sent.path("target").asString()).isEqualTo("supplier/message/send");
        assertThat(sent.path("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(sent.path("attributes").path("a2a.receipt").asString()).isEqualTo("VERIFIED");
        assertThat(receivedEntry).isNotNull();
        assertThat(receivedEntry.path("action").asString()).isEqualTo("a2a/receive");
        assertThat(receivedEntry.path("principalId").asString()).isEqualTo("alice");
        assertThat(receivedEntry.path("inputHash").asString()).isEqualTo(sent.path("inputHash").asString());
        assertThat(receivedEntry.path("outputHash").asString()).isEqualTo(sent.path("outputHash").asString());
        assertThat(receivedEntry.path("correlationId").asString())
                .isEqualTo(response.header("X-Ledger-Decision").orElseThrow());
        assertThat(receivedEntry.path("attributes").path("a2a.issuer").asString()).isEqualTo(ISSUER);
        assertThat(receivedEntry.path("attributes").path("a2a.sender").asString()).isEqualTo(agent.agentId());
        assertThat(receivedEntry.path("hash").asString()).isEqualTo(claims.evidenceHash());
        assertThat(claims.issuer()).isEqualTo(SUPPLIER_ISSUER);
        assertThat(outbound.path("receiptStatus").asString()).isEqualTo("VERIFIED");
        assertThat(outbound.path("receipt").asString()).isEqualTo(receipt);
        assertThat(SdJwt.parse(outbound.path("mandate").asString()).jwt().payload().path("aud").asString())
                .isEqualTo(SUPPLIER_ISSUER);
    }

    @Test
    void aDeniedMessageNeverLeavesTheLedger() {
        RegisteredAgent agent = agentWithGrant(List.of("bank/*"));
        int before = sales.calls();

        LedgerClient.Response response = agent.client().post("/a2a/out/supplier", message("message/send", "m-2"),
                principal());

        assertThat(response.json().path("error").path("code").asInt()).isEqualTo(-32003);
        assertThat(response.json().path("error").path("data").path("reasonCode").asString())
                .isEqualTo("NOT_COVERED");
        assertThat(sales.calls()).isEqualTo(before);
    }

    @Test
    void aFailedTaskIsRecordedAsFailedOnBothSidesWithAReceipt() {
        RegisteredAgent agent = agentWithGrant(List.of("supplier/*"));

        LedgerClient.Response response = agent.client().post("/a2a/out/supplier", message("tasks/get", "m-3"),
                principal());
        JsonNode sent = outcomeOf(agent.agentId());
        ExchangeReceipt receipt = ExchangeReceipt.fromPayload(
                Jws.parse(response.header("X-Nexusphere-Receipt").orElseThrow()).payload());

        assertThat(response.json().path("error").path("code").asInt()).isEqualTo(-32001);
        assertThat(response.header("X-Ledger-Receipt")).hasValue("VERIFIED");
        assertThat(sent.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(receipt.outcome()).isEqualTo("FAILED");
    }

    @Test
    void theReceiverRejectsARevokedMandateAndRecordsTheDenial() {
        RegisteredAgent agent = agentWithGrant(List.of("supplier/*"));
        String grantId = ledger().get("/api/v1/grants?agentId=" + agent.agentId()).json().path("items").get(0)
                .path("id").asString();
        JsonNode mandate = agent.client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", grantId, "audience", SUPPLIER_ISSUER))).json();
        ledger().post("/api/v1/mandates/" + mandate.path("id").asString() + "/revoke");
        String body = message("message/send", "m-4");
        int before = sales.calls();

        LedgerClient.Response response = deliver(mandate.path("token").asString(),
                proof(agent.agentId(), mandate.path("id").asString(), body, Instant.now()), body);
        JsonNode denied = supplier.client().get("/api/v1/evidence?agentId=sales&limit=500").json().path("items");
        JsonNode last = denied.get(denied.size() - 1);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().path("error").path("data").path("problems").get(0).asString())
                .isEqualTo("REVOKED");
        assertThat(sales.calls()).isEqualTo(before);
        assertThat(last.path("decision").asString()).isEqualTo("DENY");
        assertThat(last.path("reason").asString()).isEqualTo("REVOKED");
        assertThat(last.path("delegationId").asString()).isEqualTo(mandate.path("id").asString());
    }

    @Test
    void replayedTamperedOrForgedRequestsAreRejectedWithoutReachingTheAgent() {
        RegisteredAgent agent = agentWithGrant(List.of("supplier/*"));
        String grantId = ledger().get("/api/v1/grants?agentId=" + agent.agentId()).json().path("items").get(0)
                .path("id").asString();
        JsonNode mandate = agent.client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", grantId, "audience", SUPPLIER_ISSUER))).json();
        String token = mandate.path("token").asString();
        String body = message("message/send", "m-5");
        String proof = proof(agent.agentId(), mandate.path("id").asString(), body, Instant.now());

        LedgerClient.Response first = deliver(token, proof, body);
        int before = sales.calls();
        LedgerClient.Response replayed = deliver(token, proof, body);
        LedgerClient.Response tampered = deliver(token, proof, message("message/send", "m-6"));
        LedgerClient.Response stale = deliver(token, proof(agent.agentId(), mandate.path("id").asString(), body,
                Instant.now().minus(1, ChronoUnit.HOURS)), body);
        LedgerClient.Response forged = deliver(forgedMandate(), proof, body);
        LedgerClient.Response bare = deliver(null, null, body);

        assertThat(first.status()).isEqualTo(200);
        assertThat(replayed.status()).isEqualTo(409);
        assertThat(tampered.status()).isEqualTo(401);
        assertThat(stale.status()).isEqualTo(401);
        assertThat(forged.status()).isEqualTo(401);
        assertThat(bare.status()).isEqualTo(401);
        assertThat(sales.calls()).isEqualTo(before);
    }

    @Test
    void theReceiverCountsTheUsesOfAMandateOnItsOwn() {
        RegisteredAgent agent = registerAgent("acme");
        Map<String, Object> body = grantBody("alice", agent.agentId(), List.of("a2a/send"), List.of("supplier/*"));
        body.put("maxUses", 1);
        String grantId = grant(body);
        JsonNode mandate = agent.client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", grantId, "audience", SUPPLIER_ISSUER))).json();
        String token = mandate.path("token").asString();
        String first = message("message/send", "m-12");
        String second = message("message/send", "m-13");

        LedgerClient.Response used = deliver(token, proof(agent.agentId(), mandate.path("id").asString(), first,
                Instant.now()), first);
        int before = sales.calls();
        LedgerClient.Response exhausted = deliver(token, proof(agent.agentId(), mandate.path("id").asString(),
                second, Instant.now()), second);

        assertThat(used.status()).isEqualTo(200);
        assertThat(exhausted.status()).isEqualTo(403);
        assertThat(exhausted.json().path("error").path("data").path("problems").toString())
                .contains("USES_EXHAUSTED");
        assertThat(sales.calls()).isEqualTo(before);
    }

    @Test
    void anUnreachablePeerIsAnsweredWithoutDelivery() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("a2a/send"), List.of("*")));

        LedgerClient.Response offline = agent.client().post("/a2a/out/offline", message("message/send", "m-7"),
                principal());
        JsonNode sent = outcomeOf(agent.agentId());
        long uses = ledger().get("/api/v1/grants/" + grantId).json().path("uses").asLong();
        LedgerClient.Response operator = ledger().post("/a2a/out/supplier", message("message/send", "m-9"),
                principal());
        LedgerClient.Response unknown = agent.client().post("/a2a/out/nobody", message("message/send", "m-10"),
                principal());
        LedgerClient.Response anonymous = anonymous().post("/a2a/out/supplier", message("message/send", "m-11"),
                principal());

        assertThat(offline.status()).isEqualTo(502);
        assertThat(offline.json().path("error").path("code").asInt()).isEqualTo(-32002);
        assertThat(sent.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(sent.path("attributes").path("a2a.receipt").asString()).isEqualTo("MISSING");
        assertThat(sent.path("attributes").path("grant.useReturned").asString()).isEqualTo("true");
        assertThat(uses).isZero();
        assertThat(operator.status()).isEqualTo(403);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(anonymous.status()).isEqualTo(401);
    }

    @Test
    void aStreamedTaskIsRelayedLiveAndTheReceiptCoversEveryEvent() {
        RegisteredAgent agent = agentWithGrant(List.of("supplier/*"));
        Map<String, String> headers = new HashMap<>(principal());
        headers.put("Accept", "text/event-stream");

        LedgerClient.Response response = agent.client().post("/a2a/out/supplier", message("message/stream", "m-14"),
                headers);
        JsonNode outbound = settledExchange(agent.client(), response.header("X-Ledger-Exchange").orElseThrow());
        ExchangeReceipt receipt = ExchangeReceipt.fromPayload(Jws.parse(outbound.path("receipt").asString())
                .payload());
        JsonNode sent = outcomeOf(agent.agentId());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type").orElseThrow()).startsWith("text/event-stream");
        assertThat(response.body()).contains("\"submitted\"").contains("\"working\"").contains("\"completed\"")
                .doesNotContain("nexusphere-receipt");
        assertThat(outbound.path("receiptStatus").asString()).isEqualTo("VERIFIED");
        assertThat(outbound.path("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(receipt.outcome()).isEqualTo("SUCCEEDED");
        assertThat(receipt.responseHash()).isEqualTo(outbound.path("responseHash").asString());
        assertThat(sent.path("attributes").path("a2a.events").asString()).isEqualTo("3");
        assertThat(sent.path("attributes").path("a2a.stream").asString()).isEqualTo("COMPLETE");
        assertThat(sent.path("attributes").path("a2a.receipt").asString()).isEqualTo("VERIFIED");
    }

    @Test
    void aStreamThatEndsInAFailedTaskIsRecordedAsFailedOnBothSides() {
        RegisteredAgent agent = agentWithGrant(List.of("supplier/*"));

        LedgerClient.Response response = agent.client().post("/a2a/out/supplier",
                message("tasks/resubscribe", "m-15"), principal());
        JsonNode outbound = settledExchange(agent.client(), response.header("X-Ledger-Exchange").orElseThrow());
        ExchangeReceipt receipt = ExchangeReceipt.fromPayload(Jws.parse(outbound.path("receipt").asString())
                .payload());

        assertThat(response.body()).contains("\"failed\"");
        assertThat(outbound.path("receiptStatus").asString()).isEqualTo("VERIFIED");
        assertThat(outbound.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(receipt.outcome()).isEqualTo("FAILED");
    }

    private static JsonNode settledExchange(LedgerClient client, String exchangeId) {
        for (int attempt = 0; attempt < 50; attempt++) {
            JsonNode exchange = client.get("/api/v1/a2a/exchanges/" + exchangeId).json();
            if (exchange.path("receiptStatus").isString()) {
                return exchange;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("Exchange " + exchangeId + " has not settled");
    }

    private LedgerClient.Response deliver(String mandate, String proof, String body) {
        Map<String, String> headers = new HashMap<>();
        if (mandate != null) {
            headers.put("X-Nexusphere-Mandate", mandate);
        }
        if (proof != null) {
            headers.put("X-Nexusphere-Request", proof);
        }
        return anonymousSupplier().post("/a2a/in/sales", body, headers);
    }

    private static LedgerClient anonymousSupplier() {
        return supplier.client().withApiKey(null);
    }

    private static String proof(String agentId, String mandateId, String body, Instant at) {
        return Jws.sign(ExchangeRequest.TYPE, SigningKeys.keyIdOf(SigningKeys.decodePublic(
                        "MCowBQYDK2VwAyEAZAywHYwDVyxoGB6wLRHPgAv1MUe4LTjMnb7uYOZii3o=")),
                new ExchangeRequest(ISSUER, agentId, SUPPLIER_ISSUER, UUID.randomUUID(), UUID.fromString(mandateId),
                        "message/send", Hashes.sha256(body.getBytes(StandardCharsets.UTF_8)),
                        at.truncatedTo(ChronoUnit.SECONDS)).toPayload(), DEVELOPMENT_KEY);
    }

    private static String forgedMandate() {
        KeyPair keys = SigningKeys.generate();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return Mandates.issue(new MandateClaims(ISSUER, UUID.randomUUID(), "mallory", "alice", SUPPLIER_ISSUER,
                List.of("*"), List.of("*"), null, UUID.randomUUID(), "00".repeat(32), now, now, now.plusSeconds(600),
                ISSUER + "/public/v1/mandates/status", 0), SigningKeys.keyIdOf(SigningKeys.decodePublic(
                "MCowBQYDK2VwAyEAZAywHYwDVyxoGB6wLRHPgAv1MUe4LTjMnb7uYOZii3o=")), keys.getPrivate());
    }
}
