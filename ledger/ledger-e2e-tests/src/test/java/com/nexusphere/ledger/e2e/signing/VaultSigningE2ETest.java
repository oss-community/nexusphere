package com.nexusphere.ledger.e2e.signing;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultSigningE2ETest {

    private static final String TOKEN = "nexusphere-vault-root";

    private final KeyPair local = SigningKeys.generate();
    private final int port = freePort();
    private final HttpClient http = HttpClient.newHttpClient();
    private PostgreSQLContainer postgres;
    private GenericContainer<?> vault;

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        vault = new GenericContainer<>(DockerImageName.parse("hashicorp/vault:2.1"))
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", TOKEN)
                .withEnv("SKIP_SETCAP", "true")
                .withExposedPorts(8200)
                .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));
        vault.start();
        vault("POST", "/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
        vault("POST", "/v1/transit/keys/nexusphere-ledger", "{\"type\":\"ed25519\"}");
    }

    @AfterEach
    void stop() {
        vault.stop();
        postgres.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String vaultAddress() {
        return "http://" + vault.getHost() + ":" + vault.getMappedPort(8200);
    }

    private void vault(String method, String path, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(vaultAddress() + path))
                .header("X-Vault-Token", TOKEN)
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
            assertThat(status).as(method + " " + path).isBetween(200, 299);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private StandaloneLedger startWithVault(String... extra) {
        String[] args = new String[extra.length + 3];
        args[0] = "--ledger.signing.provider=vault";
        args[1] = "--ledger.signing.vault.address=" + vaultAddress();
        args[2] = "--ledger.signing.vault.token=" + TOKEN;
        System.arraycopy(extra, 0, args, 3, extra.length);
        return StandaloneLedger.start(postgres, port, local, args);
    }

    private static void record(LedgerClient ledger, String target) {
        ledger.recordEvidence(Map.of("agentId", "agent-a", "principalId", "alice", "action", "tools/call",
                "target", target, "decision", "ALLOW", "outcome", "SUCCEEDED"));
        assertThat(ledger.post("/api/v1/checkpoints").status()).isEqualTo(200);
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

    @Test
    void movesFromALocalKeyToVaultAndFollowsAVaultRotation() {
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, port, local)) {
            record(ledger.client(), "search");
        }
        String vaultKeyId;
        try (StandaloneLedger ledger = startWithVault(
                "--ledger.signing.previous-private-key=" + SigningKeys.encode(local.getPrivate()))) {
            LedgerClient client = ledger.client();
            record(client, "send_email");
            JsonNode keys = client.get("/api/v1/keys").json();
            assertThat(keys).hasSize(2);
            vaultKeyId = keys.get(1).path("keyId").asString();
            assertThat(keys.get(1).path("rotation").path("previousKeySignature").isString()).isTrue();

            String token = mandate(client);
            MandateCheck check = MandateVerifier.builder().trustIssuer(ledger.baseUrl())
                    .audience("https://supplier.test").cacheTtl(Duration.ZERO).build().verify(token);
            assertThat(check.problems()).isEmpty();
            assertThat(client.getBytes("/api/v1/evidence/" + client.get("/api/v1/evidence?limit=1").json()
                    .path("items").get(0).path("id").asString() + "/statement")).isNotEmpty();
        }

        vault("POST", "/v1/transit/keys/nexusphere-ledger/rotate", "{}");
        try (StandaloneLedger ledger = startWithVault()) {
            LedgerClient client = ledger.client();
            record(client, "read_file");
            JsonNode keys = client.get("/api/v1/keys").json();
            assertThat(keys).hasSize(3);
            assertThat(keys.get(2).path("rotation").path("previousKeyId").asString()).isEqualTo(vaultKeyId);
            assertThat(keys.get(2).path("rotation").path("previousKeySignature").isString()).isTrue();
            assertThat(client.get("/api/v1/verification").json().path("valid").asBoolean()).isTrue();

            JsonNode pkg = client.post("/api/v1/packages", "{}").json();
            PackageReport report = PackageVerifier.verify(pkg, SigningKeys.encode(local.getPublic()));
            assertThat(report.problems()).isEmpty();
            assertThat(report.keyId()).isEqualTo(keys.get(2).path("keyId").asString());
        }
    }

    @Test
    void refusesToStartWithoutAUsableVaultKey() {
        assertThatThrownBy(() -> startWithVault("--ledger.signing.vault.token=wrong-token"))
                .hasStackTraceContaining("Vault answered 403");
        vault("POST", "/v1/transit/keys/aes-key", "{\"type\":\"aes256-gcm96\"}");
        assertThatThrownBy(() -> startWithVault("--ledger.signing.vault.key=aes-key"))
                .hasStackTraceContaining("must be of type ed25519");
    }
}
