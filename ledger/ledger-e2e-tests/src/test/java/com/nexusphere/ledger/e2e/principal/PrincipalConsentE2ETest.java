package com.nexusphere.ledger.e2e.principal;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.FakeOidcProvider;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrincipalConsentE2ETest {

    private static PostgreSQLContainer postgres;
    private static FakeOidcProvider oidc;
    private static StandaloneLedger ledger;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        oidc = FakeOidcProvider.start();
        ledger = StandaloneLedger.start(postgres, freePort(), SigningKeys.generate(),
                "--ledger.oidc.issuer=" + oidc.issuer(),
                "--ledger.oidc.audience=" + FakeOidcProvider.AUDIENCE,
                "--ledger.oidc.principal-claim=preferred_username");
    }

    @AfterAll
    static void stop() {
        ledger.close();
        oidc.close();
        postgres.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static LedgerClient operator() {
        return ledger.client();
    }

    private static LedgerClient as(String token) {
        return ledger.client().withApiKey(token);
    }

    private static LedgerClient agent(String agentId) {
        String apiKey = operator().post("/api/v1/agents", LedgerClient.json(Map.of("agentId", agentId,
                "name", "Agent", "ownerId", "acme"))).json().path("apiKey").asString();
        return ledger.client().withApiKey(apiKey);
    }

    private static Map<String, Object> grantBody(String principalId, String agentId) {
        Map<String, Object> body = new HashMap<>();
        if (principalId != null) {
            body.put("principalId", principalId);
        }
        body.put("agentId", agentId);
        body.put("actions", List.of("tools/call"));
        body.put("targets", List.of("files/*"));
        body.put("expiresAt", Instant.now().plus(1, ChronoUnit.HOURS).toString());
        return body;
    }

    private static JsonNode decide(LedgerClient agent, String principalId) {
        return agent.post("/api/v1/decisions", LedgerClient.json(Map.of("principalId", principalId,
                "action", "tools/call", "target", "files/read_file"))).json();
    }

    private static JsonNode evidence(String action, String grantId) {
        for (JsonNode entry : operator().get("/api/v1/evidence?limit=500").json().path("items")) {
            if (entry.path("action").asString().equals(action) && entry.path("target").asString().equals(grantId)) {
                return entry;
            }
        }
        throw new IllegalStateException("No " + action + " evidence for grant " + grantId);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @Test
    void aPrincipalSignsInWithAnOidcTokenThatOnlyOpensThePrincipalApi() {
        String token = oidc.token("alice");

        JsonNode me = as(token).get("/api/v1/principal").json();
        LedgerClient.Response anonymous = ledger.client().withApiKey(null).get("/api/v1/principal");
        LedgerClient.Response forged = as(oidc.forgedToken("alice")).get("/api/v1/principal");
        LedgerClient.Response otherAudience = as(oidc.token("alice", Map.of("aud", List.of("another-app"))))
                .get("/api/v1/principal");
        LedgerClient.Response expired = as(oidc.token("alice", Map.of("exp",
                Instant.now().minus(10, ChronoUnit.MINUTES).getEpochSecond()))).get("/api/v1/principal");
        LedgerClient.Response otherIssuer = as(oidc.token("alice", Map.of("iss", "https://elsewhere.example")))
                .get("/api/v1/principal");
        LedgerClient.Response evidence = as(token).get("/api/v1/evidence");
        LedgerClient.Response operatorOnPrincipal = operator().get("/api/v1/principal");
        JsonNode discovery = ledger.client().withApiKey(null).get("/public/v1/oidc").json();

        assertThat(me.path("principalId").asString()).isEqualTo("alice");
        assertThat(me.path("name").asString()).isEqualTo("Alice");
        assertThat(me.path("issuer").asString()).isEqualTo(oidc.issuer());
        assertThat(me.path("subject").asString()).isEqualTo("subject-alice");
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(forged.status()).isEqualTo(401);
        assertThat(otherAudience.status()).isEqualTo(401);
        assertThat(expired.status()).isEqualTo(401);
        assertThat(otherIssuer.status()).isEqualTo(401);
        assertThat(evidence.status()).isEqualTo(403);
        assertThat(operatorOnPrincipal.status()).isEqualTo(403);
        assertThat(discovery.path("issuer").asString()).isEqualTo(oidc.issuer());
        assertThat(discovery.path("clientId").asString()).isEqualTo(FakeOidcProvider.AUDIENCE);
    }

    @Test
    void anOperatorGrantWaitsForThePrincipalsConsent() {
        String agentId = unique("agent");
        LedgerClient agent = agent(agentId);
        String token = oidc.token("bob");

        JsonNode created = operator().post("/api/v1/grants", LedgerClient.json(grantBody("bob", agentId))).json();
        String grantId = created.path("id").asString();
        JsonNode before = decide(agent, "bob");
        LedgerClient.Response mandate = agent.post("/api/v1/mandates", LedgerClient.json(Map.of("grantId", grantId,
                "audience", "https://supplier.test")));
        JsonNode pending = as(token).get("/api/v1/principal/grants?state=PENDING").json().path("items");
        JsonNode approved = as(token).post("/api/v1/principal/grants/" + grantId + "/approve").json();
        JsonNode after = decide(agent, "bob");
        JsonNode consent = evidence("grant/approve", grantId);

        assertThat(created.path("status").asString()).isEqualTo("PENDING");
        assertThat(created.path("consent").isNull()).isTrue();
        assertThat(evidence("grant/create", grantId).path("attributes").path("consent").asString())
                .isEqualTo("PENDING");
        assertThat(before.path("decision").asString()).isEqualTo("DENY");
        assertThat(mandate.status()).isEqualTo(409);
        assertThat(pending.findValuesAsString("id")).contains(grantId);
        assertThat(approved.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(approved.path("consent").asString()).isEqualTo("PRINCIPAL");
        assertThat(approved.path("consentedAt").isString()).isTrue();
        assertThat(after.path("decision").asString()).isEqualTo("ALLOW");
        assertThat(after.path("grantId").asString()).isEqualTo(grantId);
        assertThat(consent.path("principalId").asString()).isEqualTo("bob");
        assertThat(consent.path("agentId").asString()).isEqualTo(agentId);
        assertThat(consent.path("inputHash").asString()).isEqualTo(created.path("termsHash").asString());
        assertThat(consent.path("attributes").path("consent.issuer").asString()).isEqualTo(oidc.issuer());
        assertThat(consent.path("attributes").path("consent.subject").asString()).isEqualTo("subject-bob");
        assertThat(consent.path("attributes").path("consent.token").asString())
                .isEqualTo(Hashes.sha256(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(consent.path("attributes").path("consent.authTime").isString()).isTrue();
        assertThat(as(token).post("/api/v1/principal/grants/" + grantId + "/approve").status()).isEqualTo(409);
    }

    @Test
    void anAgentAsksForAGrantAndThePrincipalDeniesIt() {
        String agentId = unique("agent");
        LedgerClient agent = agent(agentId);
        String token = oidc.token("carol");

        LedgerClient.Response asked = agent.post("/api/v1/grants", LedgerClient.json(grantBody("carol", agentId)));
        LedgerClient.Response forOther = agent.post("/api/v1/grants",
                LedgerClient.json(grantBody("carol", unique("agent"))));
        String grantId = asked.json().path("id").asString();
        JsonNode denied = as(token).post("/api/v1/principal/grants/" + grantId + "/deny",
                LedgerClient.json(Map.of("reason", "Not this agent"))).json();
        LedgerClient.Response approveAfter = as(token).post("/api/v1/principal/grants/" + grantId + "/approve");
        JsonNode decision = decide(agent, "carol");

        assertThat(asked.status()).isEqualTo(201);
        assertThat(asked.json().path("status").asString()).isEqualTo("PENDING");
        assertThat(forOther.status()).isEqualTo(403);
        assertThat(denied.path("status").asString()).isEqualTo("DENIED");
        assertThat(denied.path("revokeReason").asString()).isEqualTo("Not this agent");
        assertThat(approveAfter.status()).isEqualTo(409);
        assertThat(decision.path("decision").asString()).isEqualTo("DENY");
        assertThat(evidence("grant/deny", grantId).path("attributes").path("consent.subject").asString())
                .isEqualTo("subject-carol");
    }

    @Test
    void aPrincipalGrantsAndRevokesOnItsOwn() {
        String agentId = unique("agent");
        LedgerClient agent = agent(agentId);
        String token = oidc.token("dave");

        LedgerClient.Response created = as(token).post("/api/v1/principal/grants",
                LedgerClient.json(grantBody(null, agentId)));
        String grantId = created.json().path("id").asString();
        JsonNode mandate = agent.post("/api/v1/mandates", LedgerClient.json(Map.of("grantId", grantId,
                "audience", "https://supplier.test"))).json();
        JsonNode revoked = as(token).post("/api/v1/principal/grants/" + grantId + "/revoke",
                LedgerClient.json(Map.of("reason", "Done"))).json();
        JsonNode mandateAfter = agent.get("/api/v1/mandates/" + mandate.path("id").asString()).json();

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("principalId").asString()).isEqualTo("dave");
        assertThat(created.json().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.json().path("consent").asString()).isEqualTo("PRINCIPAL");
        assertThat(evidence("grant/create", grantId).path("attributes").path("consent.subject").asString())
                .isEqualTo("subject-dave");
        assertThat(revoked.path("status").asString()).isEqualTo("REVOKED");
        assertThat(mandateAfter.path("status").asString()).isEqualTo("REVOKED");
        assertThat(evidence("grant/revoke", grantId).path("attributes").path("consent.subject").asString())
                .isEqualTo("subject-dave");
    }

    @Test
    void aPrincipalCannotSeeOrDecideAnotherPrincipalsGrants() {
        String agentId = unique("agent");
        agent(agentId);
        String erin = oidc.token("erin");
        String mallory = oidc.token("mallory");
        String grantId = operator().post("/api/v1/grants", LedgerClient.json(grantBody("erin", agentId))).json()
                .path("id").asString();

        LedgerClient.Response read = as(mallory).get("/api/v1/principal/grants/" + grantId);
        LedgerClient.Response approve = as(mallory).post("/api/v1/principal/grants/" + grantId + "/approve");
        LedgerClient.Response revoke = as(mallory).post("/api/v1/principal/grants/" + grantId + "/revoke");
        LedgerClient.Response onBehalf = as(mallory).post("/api/v1/principal/grants",
                LedgerClient.json(grantBody("erin", agentId)));
        JsonNode listed = as(mallory).get("/api/v1/principal/grants").json().path("items");
        JsonNode malloryEvidence = as(mallory).get("/api/v1/principal/evidence").json().path("items");
        JsonNode erinEvidence = as(erin).get("/api/v1/principal/evidence").json().path("items");

        assertThat(read.status()).isEqualTo(404);
        assertThat(approve.status()).isEqualTo(404);
        assertThat(revoke.status()).isEqualTo(404);
        assertThat(onBehalf.status()).isEqualTo(403);
        assertThat(listed.findValuesAsString("id")).doesNotContain(grantId);
        assertThat(malloryEvidence.findValuesAsString("target")).doesNotContain(grantId);
        assertThat(erinEvidence.findValuesAsString("target")).contains(grantId);
        assertThat(erinEvidence.findValuesAsString("principalId")).containsOnly("erin");
        assertThat(as(erin).get("/api/v1/principal/grants/" + grantId).json().path("status").asString())
                .isEqualTo("PENDING");
    }
}
