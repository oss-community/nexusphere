package com.nexusphere.ledger.e2e.a2a;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.FakeA2aAgent;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.e2e.support.SupplierLedger;
import com.nexusphere.ledger.mandate.ExchangeRequest;
import com.nexusphere.ledger.mandate.HttpSignatures;
import com.nexusphere.ledger.mandate.Jws;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationE2ETest extends LedgerE2ETestBase {

    private static final PrivateKey DEVELOPMENT_KEY =
            SigningKeys.decodePrivate("MC4CAQAwBQYDK2VwBCIEIDPXxsOX77t9k2XIr5aVh1AORIeW8w4rXooP+vMeOPKL");
    private static final String DEVELOPMENT_KEY_ID = SigningKeys.keyIdOf(SigningKeys.decodePublic(
            "MCowBQYDK2VwAyEAZAywHYwDVyxoGB6wLRHPgAv1MUe4LTjMnb7uYOZii3o="));

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

    private static String message(String id) {
        return LedgerClient.json(Map.of("jsonrpc", "2.0", "id", id, "method", "message/send", "params",
                Map.of("message", Map.of("role", "user", "messageId", id, "parts",
                        List.of(Map.of("kind", "text", "text", "Order 40 pallets"))))));
    }

    private static Instant nextSecond() throws InterruptedException {
        Instant from = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        Thread.sleep(Math.max(0, from.toEpochMilli() - System.currentTimeMillis() + 5));
        return from;
    }

    private static JsonNode statement(LedgerClient client, String peer, Instant from, Instant to) {
        LedgerClient.Response response = client.post("/api/v1/a2a/statements", LedgerClient.json(Map.of(
                "peer", peer, "from", from.toString(), "to", to.toString())));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private static JsonNode reconcile(LedgerClient client, String statement) {
        LedgerClient.Response response = client.post("/api/v1/a2a/reconciliations",
                LedgerClient.json(Map.of("statement", statement)));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private RegisteredAgent agentWithGrant() {
        RegisteredAgent agent = registerAgent("acme");
        grant(grantBody("alice", agent.agentId(), List.of("a2a/send"), List.of("supplier/*")));
        return agent;
    }

    @Test
    void bothLedgersReconcileTheExchangesBetweenThem() throws InterruptedException {
        Instant from = nextSecond();
        RegisteredAgent agent = agentWithGrant();
        for (String id : List.of("r-1", "r-2")) {
            assertThat(agent.client().post("/a2a/out/supplier", message(id), Map.of("X-Ledger-Principal", "alice"))
                    .status()).isEqualTo(200);
        }
        Instant to = from.plus(1, ChronoUnit.HOURS);

        JsonNode ours = statement(ledger(), "supplier", from, to);
        JsonNode theirs = statement(supplier.client(), ISSUER, from, to);
        JsonNode atSupplier = reconcile(supplier.client(), ours.path("statement").asString());
        JsonNode atAcme = reconcile(ledger(), theirs.path("statement").asString());

        assertThat(ours.path("exchanges").asInt()).isEqualTo(2);
        assertThat(ours.path("peer").asString()).isEqualTo(SUPPLIER_ISSUER);
        assertThat(theirs.path("exchanges").asInt()).isEqualTo(2);
        assertThat(atSupplier.path("reconciled").asBoolean()).as(atSupplier.toString()).isTrue();
        assertThat(atSupplier.path("matched").asInt()).isEqualTo(2);
        assertThat(atAcme.path("reconciled").asBoolean()).isTrue();
        assertThat(atAcme.path("matched").asInt()).isEqualTo(2);
        JsonNode recorded = supplier.client().get("/api/v1/evidence?agentId=ledger&limit=500").json().path("items");
        JsonNode last = recorded.get(recorded.size() - 1);
        assertThat(last.path("action").asString()).isEqualTo("a2a/reconcile");
        assertThat(last.path("sequence").asLong()).isEqualTo(atSupplier.path("evidenceSequence").asLong());
        assertThat(last.path("target").asString()).isEqualTo(ISSUER);
        assertThat(last.path("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(last.path("inputHash").asString()).isEqualTo(Hashes.sha256(ours.path("statement").asString()
                .getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void aRequestSentAroundTheSendingLedgerIsFound() throws InterruptedException {
        Instant from = nextSecond();
        RegisteredAgent agent = agentWithGrant();
        assertThat(agent.client().post("/a2a/out/supplier", message("s-1"), Map.of("X-Ledger-Principal", "alice"))
                .status()).isEqualTo(200);
        String grantId = ledger().get("/api/v1/grants?agentId=" + agent.agentId()).json().path("items").get(0)
                .path("id").asString();
        JsonNode mandate = agent.client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", grantId, "audience", SUPPLIER_ISSUER))).json();
        String body = message("s-2");
        UUID smuggled = UUID.randomUUID();
        String proof = Jws.sign(ExchangeRequest.TYPE, DEVELOPMENT_KEY_ID, new ExchangeRequest(ISSUER,
                agent.agentId(), SUPPLIER_ISSUER, smuggled, UUID.fromString(mandate.path("id").asString()),
                "message/send", Hashes.sha256(body.getBytes(StandardCharsets.UTF_8)),
                Instant.now().truncatedTo(ChronoUnit.SECONDS)).toPayload(), DEVELOPMENT_KEY);
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Nexusphere-Mandate", mandate.path("token").asString());
        headers.put("X-Nexusphere-Request", proof);
        headers.putAll(HttpSignatures.sign("POST", URI.create(SUPPLIER_ISSUER + "/a2a/in/sales"),
                body.getBytes(StandardCharsets.UTF_8), DEVELOPMENT_KEY_ID, Signer.of(DEVELOPMENT_KEY), Instant.now(),
                UUID.randomUUID().toString()));
        assertThat(supplier.client().withApiKey(null).post("/a2a/in/sales", body, headers).status()).isEqualTo(200);
        Instant to = from.plus(1, ChronoUnit.HOURS);

        JsonNode theirs = statement(supplier.client(), ISSUER, from, to);
        JsonNode result = reconcile(ledger(), theirs.path("statement").asString());

        assertThat(result.path("reconciled").asBoolean()).isFalse();
        assertThat(result.path("matched").asInt()).isEqualTo(1);
        assertThat(result.path("discrepancies")).hasSize(1);
        JsonNode found = result.path("discrepancies").get(0);
        assertThat(found.path("kind").asString()).isEqualTo("NOT_SENT");
        assertThat(found.path("requestId").asString()).isEqualTo(smuggled.toString());
        assertThat(found.path("sender").asString()).isEqualTo(ISSUER);
    }

    @Test
    void onlyTheOperatorAsksAndOnlyKnownPeersAreTrusted() {
        RegisteredAgent agent = registerAgent("acme");
        Instant now = Instant.now();
        String body = LedgerClient.json(Map.of("peer", "supplier", "from", now.toString(),
                "to", now.plusSeconds(60).toString()));

        assertThat(agent.client().post("/api/v1/a2a/statements", body).status()).isEqualTo(403);
        LedgerClient.Response unknown = ledger().post("/api/v1/a2a/statements", LedgerClient.json(Map.of(
                "peer", "https://stranger.test", "from", now.toString(), "to", now.plusSeconds(60).toString())));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.json().path("details").path("fields").path("peer").asString())
                .isEqualTo("must be a configured peer name or a trusted issuer");
        LedgerClient.Response tooLong = ledger().post("/api/v1/a2a/statements", LedgerClient.json(Map.of(
                "peer", "supplier", "from", now.toString(), "to", now.plus(40, ChronoUnit.DAYS).toString())));
        assertThat(tooLong.status()).isEqualTo(400);
        String own = statement(ledger(), "supplier", now, now.plusSeconds(60)).path("statement").asString();
        LedgerClient.Response self = ledger().post("/api/v1/a2a/reconciliations",
                LedgerClient.json(Map.of("statement", own)));
        assertThat(self.status()).isEqualTo(400);
    }
}
