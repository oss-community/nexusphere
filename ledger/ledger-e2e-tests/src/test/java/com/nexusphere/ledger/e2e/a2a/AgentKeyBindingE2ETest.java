package com.nexusphere.ledger.e2e.a2a;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.FakeA2aAgent;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.e2e.support.SupplierLedger;
import com.nexusphere.ledger.mandate.ExchangeRequest;
import com.nexusphere.ledger.mandate.Jws;
import com.nexusphere.ledger.mandate.KeyBinding;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentKeyBindingE2ETest extends LedgerE2ETestBase {

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

    private record KeyedAgent(RegisteredAgent agent, KeyPair keys, String grantId) {
    }

    private KeyedAgent keyedAgent() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("a2a/send"), List.of("supplier/*")));
        KeyPair keys = SigningKeys.generate();
        LedgerClient.Response set = agent.client().put("/api/v1/agents/" + agent.agentId() + "/signing-key",
                LedgerClient.json(Map.of("publicKey", SigningKeys.encode(keys.getPublic()))));
        assertThat(set.status()).isEqualTo(200);
        return new KeyedAgent(agent, keys, grantId);
    }

    private String mandate(KeyedAgent keyed) {
        return keyed.agent().client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", keyed.grantId(), "audience", SUPPLIER_ISSUER))).json()
                .path("token").asString();
    }

    private static String message(String id) {
        return LedgerClient.json(Map.of("jsonrpc", "2.0", "id", id, "method", "message/send", "params",
                Map.of("message", Map.of("role", "user", "messageId", id, "parts",
                        List.of(Map.of("kind", "text", "text", "Order 40 pallets"))))));
    }

    private static String nonce(String body) {
        return Hashes.sha256(body.getBytes(StandardCharsets.UTF_8));
    }

    private static LedgerClient.Response send(KeyedAgent keyed, String body, String presentation) {
        return keyed.agent().client().post("/a2a/out/supplier", body, presentation == null
                ? Map.of("X-Ledger-Principal", "alice")
                : Map.of("X-Ledger-Principal", "alice", "X-Nexusphere-Mandate", presentation));
    }

    @Test
    void theAgentRegistersItsKeyAndOnlyAnOperatorCanReplaceIt() {
        KeyedAgent keyed = keyedAgent();
        String agentPath = "/api/v1/agents/" + keyed.agent().agentId();
        String other = LedgerClient.json(Map.of("publicKey", SigningKeys.encode(SigningKeys.generate().getPublic())));

        LedgerClient.Response byAgent = keyed.agent().client().put(agentPath + "/signing-key", other);
        LedgerClient.Response invalid = ledger().put(agentPath + "/signing-key",
                LedgerClient.json(Map.of("publicKey", "not-a-key")));
        LedgerClient.Response byOperator = ledger().put(agentPath + "/signing-key", other);
        JsonNode items = ledger().get("/api/v1/evidence?agentId=" + keyed.agent().agentId()).json().path("items");

        assertThat(byAgent.status()).isEqualTo(403);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(byOperator.status()).isEqualTo(200);
        assertThat(byOperator.json().path("signingKeyId").asString())
                .isNotEqualTo(SigningKeys.keyIdOf(keyed.keys().getPublic()));
        assertThat(items.findValuesAsString("action")).containsSubsequence("agent/signing-key", "agent/signing-key");
        assertThat(items.get(items.size() - 1).path("attributes").path("previousSigningKeyId").asString())
                .isEqualTo(SigningKeys.keyIdOf(keyed.keys().getPublic()));
    }

    @Test
    void aMandateNamesTheAgentKeyInItsConfirmationClaim() {
        KeyedAgent keyed = keyedAgent();

        MandateClaims claims = Mandates.claims(mandate(keyed));

        assertThat(claims.bound()).isTrue();
        assertThat(claims.holderKey()).isEqualTo(keyed.keys().getPublic());
    }

    @Test
    void aKeyBoundPresentationIsAcceptedOnBothLedgers() {
        KeyedAgent keyed = keyedAgent();
        String body = message("kb-1");
        String presentation = KeyBinding.present(mandate(keyed), keyed.keys().getPrivate(), SUPPLIER_ISSUER,
                nonce(body), Instant.now());

        LedgerClient.Response response = send(keyed, body, presentation);
        JsonNode exchange = keyed.agent().client().get("/api/v1/a2a/exchanges/"
                + response.header("X-Ledger-Exchange").orElseThrow()).json();
        JsonNode items = ledger().get("/api/v1/evidence?agentId=" + keyed.agent().agentId()).json().path("items");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("X-Ledger-Receipt")).hasValue("VERIFIED");
        assertThat(SdJwt.parse(exchange.path("mandate").asString()).keyBinding()).isNotNull();
        assertThat(items.get(items.size() - 1).path("attributes").path("a2a.keyBinding").asString())
                .isEqualTo(SigningKeys.keyIdOf(keyed.keys().getPublic()));
    }

    @Test
    void anApiKeyAloneIsNotEnoughOnceTheAgentHasAKey() {
        KeyedAgent keyed = keyedAgent();
        String body = message("kb-2");
        int before = sales.calls();

        LedgerClient.Response bare = send(keyed, body, null);
        LedgerClient.Response unbound = send(keyed, body, mandate(keyed));
        LedgerClient.Response stolen = send(keyed, body, KeyBinding.present(mandate(keyed),
                SigningKeys.generate().getPrivate(), SUPPLIER_ISSUER, nonce(body), Instant.now()));
        LedgerClient.Response replayed = send(keyed, body, KeyBinding.present(mandate(keyed),
                keyed.keys().getPrivate(), SUPPLIER_ISSUER, nonce(message("kb-other")), Instant.now()));

        assertThat(bare.status()).isEqualTo(400);
        assertThat(unbound.status()).isEqualTo(403);
        assertThat(problems(unbound)).contains("KEY_BINDING_MISSING");
        assertThat(stolen.status()).isEqualTo(403);
        assertThat(problems(stolen)).contains("KEY_BINDING_INVALID");
        assertThat(replayed.status()).isEqualTo(403);
        assertThat(problems(replayed)).contains("KEY_BINDING_INVALID");
        assertThat(sales.calls()).isEqualTo(before);
    }

    @Test
    void aMandateBoundToAReplacedKeyIsRefused() {
        KeyedAgent keyed = keyedAgent();
        String token = mandate(keyed);
        ledger().put("/api/v1/agents/" + keyed.agent().agentId() + "/signing-key",
                LedgerClient.json(Map.of("publicKey", SigningKeys.encode(SigningKeys.generate().getPublic()))));
        String body = message("kb-3");

        LedgerClient.Response response = send(keyed, body, KeyBinding.present(token, keyed.keys().getPrivate(),
                SUPPLIER_ISSUER, nonce(body), Instant.now()));

        assertThat(response.status()).isEqualTo(403);
        assertThat(problems(response)).contains("STALE_KEY");
    }

    @Test
    void theReceivingLedgerRejectsABoundMandateWithoutKeyBinding() {
        KeyedAgent keyed = keyedAgent();
        String token = mandate(keyed);
        String body = message("kb-4");
        String proof = Jws.sign(ExchangeRequest.TYPE, SigningKeys.keyIdOf(SigningKeys.decodePublic(
                        "MCowBQYDK2VwAyEAZAywHYwDVyxoGB6wLRHPgAv1MUe4LTjMnb7uYOZii3o=")),
                new ExchangeRequest(ISSUER, keyed.agent().agentId(), SUPPLIER_ISSUER, UUID.randomUUID(),
                        Mandates.claims(token).mandateId(), "message/send", nonce(body),
                        Instant.now().truncatedTo(ChronoUnit.SECONDS)).toPayload(), DEVELOPMENT_KEY);
        int before = sales.calls();

        LedgerClient.Response response = supplier.client().withApiKey(null).post("/a2a/in/sales", body,
                Map.of("X-Nexusphere-Mandate", token, "X-Nexusphere-Request", proof));

        assertThat(response.status()).isEqualTo(401);
        assertThat(problems(response)).contains("KEY_BINDING_MISSING");
        assertThat(sales.calls()).isEqualTo(before);
    }

    private static List<String> problems(LedgerClient.Response response) {
        return response.json().path("error").path("data").path("problems").valueStream().map(JsonNode::asString)
                .toList();
    }
}
