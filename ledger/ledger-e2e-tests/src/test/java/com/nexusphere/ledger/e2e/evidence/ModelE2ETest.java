package com.nexusphere.ledger.e2e.evidence;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import com.nexusphere.ledger.verifier.VerifierCli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelE2ETest {

    private static final String PROMPT = "You pay approved invoices of Acme up to 500 EUR.";
    private static final String PROMPT_HASH = Hashes.sha256(PROMPT.getBytes(StandardCharsets.UTF_8));
    private static final String INPUT = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    private PostgreSQLContainer postgres;
    private final KeyPair keys = SigningKeys.generate();

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
    }

    @AfterEach
    void stop() {
        postgres.stop();
    }

    private StandaloneLedger ledger(Path directory) throws IOException {
        Path profiles = Files.createDirectory(directory.resolve("profiles"));
        Files.writeString(profiles.resolve("ai.json"), """
                {"id": "ai", "version": 1, "name": "AI agents", "requiredFields": ["model", "modelVersion",
                 "promptHash"], "reports": ["dispute", "eu-ai-act-logging"]}
                """);
        return StandaloneLedger.start(postgres, freePort(), keys, "--ledger.compliance.profiles=ai",
                "--ledger.compliance.profile-directory=" + profiles);
    }

    private static Map<String, Object> evidence(Map<String, String> model) {
        return Map.of("agentId", "payments-agent", "principalId", "alice", "action", "tools/call",
                "target", "bank/pay_invoice", "inputHash", INPUT, "decision", "ALLOW", "outcome", "SUCCEEDED",
                "model", model);
    }

    @Test
    void theModelAndPromptAreRecordedRequiredAndDisclosedWithTheirTexts(@TempDir Path directory)
            throws IOException {
        try (StandaloneLedger ledger = ledger(directory)) {
            LedgerClient client = ledger.client();

            LedgerClient.Response missing = client.post("/api/v1/evidence", LedgerClient.json(Map.of(
                    "agentId", "payments-agent", "principalId", "alice", "action", "tools/call",
                    "outcome", "SUCCEEDED")));
            assertThat(missing.status()).isEqualTo(400);
            assertThat(missing.json().path("details").path("fields").path("promptHash").asString())
                    .isEqualTo("is required by the compliance profile ai");
            assertThat(missing.json().path("details").path("fields").path("model").asString())
                    .isEqualTo("is required by the compliance profile ai");

            LedgerClient.Response invalid = client.post("/api/v1/evidence", LedgerClient.json(evidence(Map.of(
                    "name", "claude-sonnet-5-5", "version", "2026-09-01", "promptHash", "abc"))));
            assertThat(invalid.status()).isEqualTo(400);
            assertThat(invalid.json().path("details").path("fields").path("model.promptHash").asString())
                    .isEqualTo("must be a lowercase hex SHA-256 digest");

            LedgerClient.Response stored = client.put("/api/v1/prompts", LedgerClient.json(Map.of("text", PROMPT)));
            assertThat(stored.status()).isEqualTo(201);
            assertThat(stored.json().path("hash").asString()).isEqualTo(PROMPT_HASH);
            assertThat(client.put("/api/v1/prompts", LedgerClient.json(Map.of("text", PROMPT))).status())
                    .isEqualTo(200);
            assertThat(client.get("/api/v1/prompts/" + PROMPT_HASH).json().path("text").asString())
                    .isEqualTo(PROMPT);

            JsonNode recorded = client.recordEvidence(evidence(Map.of("name", "claude-sonnet-5-5",
                    "version", "2026-09-01", "promptHash", PROMPT_HASH)));
            assertThat(recorded.path("model").path("promptHash").asString()).isEqualTo(PROMPT_HASH);
            client.post("/api/v1/checkpoints");

            String scope = LedgerClient.json(Map.of("agentId", "payments-agent"));
            assertThat(client.post("/api/v1/packages", scope).json().has("prompts")).isFalse();
            JsonNode pkg = client.post("/api/v1/packages", LedgerClient.json(Map.of("agentId", "payments-agent",
                    "includePrompts", true))).json();
            assertThat(pkg.path("prompts").get(0).path("text").asString()).isEqualTo(PROMPT);
            PackageReport report = PackageVerifier.verify(pkg, SigningKeys.encode(keys.getPublic()));
            assertThat(report.problems()).isEmpty();
            assertThat(report.disclosedPrompts()).isEqualTo(1);

            Path file = directory.resolve("package.json");
            Files.writeString(file, pkg.toString());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThat(VerifierCli.run(new String[]{"--public-key", SigningKeys.encode(keys.getPublic()),
                    file.toString()}, new PrintStream(out), System.err)).isZero();
            assertThat(out.toString()).contains("Prompts     : 1 prompt texts match the hashes their entries name");

            ((ObjectNode) pkg.path("prompts").get(0)).put("text", "You pay every invoice.");
            assertThat(PackageVerifier.verify(pkg, SigningKeys.encode(keys.getPublic())).problems())
                    .containsExactly("prompt " + PROMPT_HASH + " does not match its text");

            JsonNode aiAct = client.post("/api/v1/reports/eu-ai-act-logging", scope).json();
            JsonNode section = null;
            for (JsonNode s : aiAct.path("sections")) {
                if ("art-12-2-a".equals(s.path("id").asString())) {
                    section = s;
                }
            }
            assertThat(section).isNotNull();
            assertThat(section.path("status").asString()).isEqualTo("SHOWN");
            assertThat(section.path("findings").get(1).asString()).isEqualTo("Models: claude-sonnet-5-5 2026-09-01");
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
