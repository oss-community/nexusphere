package com.nexusphere.ledger.e2e.signing;

import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.mandate.MandateCheck;
import com.nexusphere.ledger.mandate.MandateVerifier;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KeyRevocationE2ETest {

    private final KeyPair first = SigningKeys.generate();
    private final KeyPair second = SigningKeys.generate();
    private final KeyPair witnessKeys = SigningKeys.generate();
    private final int port = freePort();
    private final int witnessPort = freePort();
    private final NoteKey logKey = new NoteKey("localhost:" + port, NoteKey.ED25519, first.getPublic());
    private final NoteKey witnessKey = new NoteKey("localhost:" + witnessPort, NoteKey.COSIGNATURE,
            witnessKeys.getPublic());
    private PostgreSQLContainer postgres;
    private PostgreSQLContainer witnessDatabase;
    private StandaloneLedger witness;

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        witnessDatabase = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        witnessDatabase.start();
        witness = StandaloneLedger.start(witnessDatabase, witnessPort, witnessKeys,
                "--ledger.log.watched.acme.key=" + logKey.vkey());
    }

    @AfterEach
    void stop() {
        witness.close();
        witnessDatabase.stop();
        postgres.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String keyId(KeyPair keys) {
        return SigningKeys.keyIdOf(keys.getPublic());
    }

    private StandaloneLedger ledger(KeyPair keys, String... extra) {
        String[] args = new String[extra.length + 2];
        args[0] = "--ledger.log.witnesses.partner.url=http://localhost:" + witnessPort + "/public/v1/witness";
        args[1] = "--ledger.log.witnesses.partner.key=" + witnessKey.vkey();
        System.arraycopy(extra, 0, args, 2, extra.length);
        return StandaloneLedger.start(postgres, port, keys, args);
    }

    private static String mandate(LedgerClient ledger) {
        String agentId = "agent-" + UUID.randomUUID();
        String apiKey = ledger.post("/api/v1/agents", LedgerClient.json(Map.of("agentId", agentId,
                "name", "Agent", "ownerId", "acme"))).json().path("apiKey").asString();
        String grantId = ledger.post("/api/v1/grants", LedgerClient.json(Map.of("principalId", "alice",
                "agentId", agentId, "actions", List.of("a2a/send"), "targets", List.of("supplier/*"),
                "expiresAt", Instant.now().plus(1, ChronoUnit.HOURS).toString()))).json().path("id").asString();
        return ledger.withApiKey(apiKey).post("/api/v1/mandates", LedgerClient.json(Map.of("grantId", grantId,
                "audience", "https://supplier.test"))).json().path("token").asString();
    }

    private static ObjectNode withoutCosignatures(JsonNode pkg) {
        ObjectNode copy = (ObjectNode) pkg.deepCopy();
        LogCheckpoint.Note note = LogCheckpoint.parse(pkg.path("log").path("checkpoint").asString());
        String text = note.body() + "\n" + note.signatures().getFirst().line();
        ((ObjectNode) copy.path("log")).put("checkpoint", text);
        return copy;
    }

    @Test
    void aRevokedKeyStaysValidOnlyWhereWitnessesProveTheTime() {
        JsonNode before;
        String token;
        try (StandaloneLedger ledger = ledger(first)) {
            LedgerClient client = ledger.client();
            client.recordEvidence(Map.of("agentId", "agent-a", "principalId", "alice", "action", "tools/call",
                    "target", "search", "decision", "ALLOW", "outcome", "SUCCEEDED"));
            assertThat(client.post("/api/v1/checkpoints").status()).isEqualTo(200);
            before = client.post("/api/v1/packages", "{}").json();
            token = mandate(client);
        }
        assertThat(LogCheckpoint.parse(before.path("log").path("checkpoint").asString()).cosignedBy(witnessKey))
                .isPresent();

        try (StandaloneLedger ledger = ledger(second,
                "--ledger.signing.previous-private-key=" + SigningKeys.encode(first.getPrivate()))) {
            LedgerClient client = ledger.client();
            Instant compromisedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
            while (Instant.now().isBefore(compromisedAt)) {
                Thread.onSpinWait();
            }

            assertThat(client.post("/api/v1/keys/" + keyId(second) + "/revocation", LedgerClient.json(Map.of(
                    "compromisedAt", compromisedAt.toString(), "reason", "test"))).status()).isEqualTo(409);
            assertThat(client.post("/api/v1/keys/unknown/revocation", LedgerClient.json(Map.of(
                    "compromisedAt", compromisedAt.toString(), "reason", "test"))).status()).isEqualTo(404);
            assertThat(client.post("/api/v1/keys/" + keyId(first) + "/revocation", LedgerClient.json(Map.of(
                    "compromisedAt", Instant.now().plus(1, ChronoUnit.DAYS).toString(), "reason", "test")))
                    .status()).isEqualTo(400);

            LedgerClient.Response revoked = client.post("/api/v1/keys/" + keyId(first) + "/revocation",
                    LedgerClient.json(Map.of("compromisedAt", compromisedAt.toString(),
                            "reason", "backup copy leaked")));
            assertThat(revoked.status()).isEqualTo(200);
            assertThat(revoked.json().path("revokerKeyId").asString()).isEqualTo(keyId(second));
            assertThat(client.post("/api/v1/keys/" + keyId(first) + "/revocation", LedgerClient.json(Map.of(
                    "compromisedAt", compromisedAt.toString(), "reason", "again"))).status()).isEqualTo(409);

            JsonNode keys = client.get("/api/v1/keys").json();
            assertThat(keys.get(0).path("status").asString()).isEqualTo("REVOKED");
            assertThat(keys.get(0).path("revocation").path("reason").asString()).isEqualTo("backup copy leaked");
            assertThat(keys.get(1).path("status").asString()).isEqualTo("ACTIVE");
            assertThat(client.get("/public/v1/keys").json().path("keys")).hasSize(1);
            assertThat(client.get("/api/v1/evidence?limit=500").json().path("items").valueStream()
                    .map(entry -> entry.path("action").asString() + " " + entry.path("target").asString())
                    .collect(Collectors.toList())).contains("key/revoke " + keyId(first));

            String pinned = SigningKeys.encode(second.getPublic());
            PackageReport proven = PackageVerifier.verify(before, pinned, keys, List.of(witnessKey), 1);
            assertThat(proven.problems()).isEmpty();
            assertThat(proven.revokedKeys()).containsExactly(keyId(first));

            PackageReport unwitnessed = PackageVerifier.verify(withoutCosignatures(before), pinned, keys,
                    List.of(witnessKey), 0);
            assertThat(unwitnessed.valid()).isFalse();
            assertThat(unwitnessed.problems()).anyMatch(p -> p.contains("revoked as compromised"));
            assertThat(PackageVerifier.verify(before, pinned, keys, List.of(), 0).valid()).isFalse();
            assertThat(PackageVerifier.verify(before, SigningKeys.encode(first.getPublic())).valid()).isTrue();

            JsonNode server = client.post("/api/v1/packages/verify?publicKey="
                    + java.net.URLEncoder.encode(pinned, java.nio.charset.StandardCharsets.UTF_8),
                    before.toString()).json();
            assertThat(server.path("valid").asBoolean()).isTrue();
            assertThat(server.path("revokedKeys").get(0).asString()).isEqualTo(keyId(first));

            MandateCheck check = MandateVerifier.builder().trustIssuer(ledger.baseUrl())
                    .audience("https://supplier.test").cacheTtl(Duration.ZERO).build().verify(token);
            assertThat(check.problems()).isNotEmpty();
        }
    }
}
