package com.nexusphere.ledger.e2e.mandate;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.mandate.Jwk;
import com.nexusphere.ledger.mandate.Jws;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.StatusList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MandateE2ETest extends LedgerE2ETestBase {

    @Test
    void anAgentObtainsAMandateThatAnyoneCanVerifyWithThePublishedKey() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("a2a/send"), List.of("supplier/*")));

        LedgerClient.Response issued = agent.client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", grantId, "audience", "https://supplier.test")));
        JsonNode mandate = issued.json();
        Jws.Parsed parsed = Jws.parse(mandate.path("token").asString());
        MandateClaims claims = MandateClaims.fromPayload(parsed.payload());
        JsonNode grant = ledger().get("/api/v1/grants/" + grantId).json();
        JsonNode evidence = ledger().get("/api/v1/evidence?agentId=" + agent.agentId()).json().path("items").get(2);

        assertThat(issued.status()).isEqualTo(201);
        assertThat(mandate.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(parsed.header().path("typ").asString()).isEqualTo(MandateClaims.TYPE);
        assertThat(parsed.verify(publishedKey(parsed.header().path("kid").asString()))).isTrue();
        assertThat(claims.agentId()).isEqualTo(agent.agentId());
        assertThat(claims.principalId()).isEqualTo("alice");
        assertThat(claims.audience()).isEqualTo("https://supplier.test");
        assertThat(claims.covers("a2a/send", "supplier/orders")).isTrue();
        assertThat(claims.termsHash()).isEqualTo(grant.path("termsHash").asString());
        assertThat(claims.expiresAt()).isEqualTo(Instant.parse(grant.path("expiresAt").asString())
                .truncatedTo(ChronoUnit.SECONDS));
        assertThat(claims.statusListUrl()).endsWith("/public/v1/mandates/status");
        assertThat(evidence.path("action").asString()).isEqualTo("mandate/issue");
        assertThat(evidence.path("target").asString()).isEqualTo(mandate.path("id").asString());
        assertThat(evidence.path("inputHash").asString()).isEqualTo(
                Hashes.sha256(mandate.path("token").asString().getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void revokingAMandateSetsItsBitInTheSignedStatusList() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("*"), List.of("*")));
        JsonNode first = issue(agent, grantId);
        JsonNode second = issue(agent, grantId);

        JsonNode revoked = ledger().post("/api/v1/mandates/" + first.path("id").asString() + "/revoke",
                LedgerClient.json(Map.of("reason", "Leaked"))).json();
        StatusList list = statusList();
        JsonNode last = ledger().get("/api/v1/evidence?agentId=" + agent.agentId()).json().path("items").get(4);

        assertThat(revoked.path("status").asString()).isEqualTo("REVOKED");
        assertThat(list.isRevoked(first.path("statusIndex").asLong())).isTrue();
        assertThat(list.isRevoked(second.path("statusIndex").asLong())).isFalse();
        assertThat(last.path("action").asString()).isEqualTo("mandate/revoke");
        assertThat(last.path("reason").asString()).isEqualTo("Leaked");
    }

    @Test
    void revokingTheGrantRevokesEveryMandateIssuedFromIt() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("*"), List.of("*")));
        JsonNode first = issue(agent, grantId);
        JsonNode second = issue(agent, grantId);

        ledger().post("/api/v1/grants/" + grantId + "/revoke", LedgerClient.json(Map.of("reason", "Ended")));
        StatusList list = statusList();
        JsonNode mandates = agent.client().get("/api/v1/mandates?grantId=" + grantId).json();
        LedgerClient.Response again = agent.client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", grantId)));

        assertThat(list.isRevoked(first.path("statusIndex").asLong())).isTrue();
        assertThat(list.isRevoked(second.path("statusIndex").asLong())).isTrue();
        assertThat(mandates.size()).isEqualTo(2);
        assertThat(mandates.get(0).path("status").asString()).isEqualTo("REVOKED");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.json().path("code").asString()).isEqualTo("GRANT_NOT_ACTIVE");
    }

    @Test
    void anAgentCannotObtainOrRevokeMandatesItDoesNotOwn() {
        RegisteredAgent owner = registerAgent("acme");
        RegisteredAgent other = registerAgent("acme");
        String grantId = grant(grantBody("alice", owner.agentId(), List.of("*"), List.of("*")));
        JsonNode mandate = issue(owner, grantId);

        LedgerClient.Response stolen = other.client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", grantId)));
        LedgerClient.Response read = other.client().get("/api/v1/mandates/" + mandate.path("id").asString());
        LedgerClient.Response revoke = owner.client().post("/api/v1/mandates/" + mandate.path("id").asString()
                + "/revoke");

        assertThat(stolen.status()).isEqualTo(404);
        assertThat(read.status()).isEqualTo(404);
        assertThat(revoke.status()).isEqualTo(403);
    }

    @Test
    void aMandateCannotOutliveItsGrant() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("*"), List.of("*")));

        LedgerClient.Response response = agent.client().post("/api/v1/mandates", LedgerClient.json(Map.of(
                "grantId", grantId, "expiresAt", Instant.now().plus(2, ChronoUnit.HOURS).toString())));
        LedgerClient.Response shorter = agent.client().post("/api/v1/mandates", LedgerClient.json(Map.of(
                "grantId", grantId, "expiresAt", Instant.now().plus(10, ChronoUnit.MINUTES).toString())));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("details").path("fields").has("expiresAt")).isTrue();
        assertThat(shorter.status()).isEqualTo(201);
    }

    @Test
    void theKeysAndStatusListArePublicAndSigned() {
        LedgerClient.Response keys = anonymous().get("/public/v1/keys");
        LedgerClient.Response status = anonymous().get("/public/v1/mandates/status");
        Jws.Parsed parsed = Jws.parse(status.body());

        assertThat(keys.status()).isEqualTo(200);
        assertThat(keys.header("content-type")).hasValueSatisfying(v -> assertThat(v).contains("jwk-set+json"));
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.header("content-type")).hasValueSatisfying(v -> assertThat(v).contains("statuslist+jwt"));
        assertThat(parsed.header().path("typ").asString()).isEqualTo(StatusList.TYPE);
        assertThat(parsed.verify(publishedKey(parsed.header().path("kid").asString()))).isTrue();
        assertThat(StatusList.fromPayload(parsed.payload()).size()).isEqualTo(StatusList.DEFAULT_SIZE);
        assertThat(anonymous().get("/api/v1/mandates?grantId=" + UUID.randomUUID()).status())
                .isEqualTo(401);
    }

    private JsonNode issue(RegisteredAgent agent, String grantId) {
        LedgerClient.Response response = agent.client().post("/api/v1/mandates",
                LedgerClient.json(Map.of("grantId", grantId)));
        if (response.status() != 201) {
            throw new IllegalStateException("Issuing a mandate failed: " + response.body());
        }
        return response.json();
    }

    private PublicKey publishedKey(String keyId) {
        for (JsonNode jwk : anonymous().get("/public/v1/keys").json().path("keys")) {
            if (keyId.equals(jwk.path("kid").asString())) {
                return Jwk.publicKey(jwk);
            }
        }
        throw new IllegalStateException("No published key " + keyId);
    }

    private StatusList statusList() {
        Jws.Parsed parsed = Jws.parse(anonymous().get("/public/v1/mandates/status").body());
        assertThat(parsed.verify(publishedKey(parsed.header().path("kid").asString()))).isTrue();
        return StatusList.fromPayload(parsed.payload());
    }
}
