package com.nexusphere.ledger.e2e.transparency;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.chain.TimestampToken;
import com.nexusphere.ledger.e2e.support.FakeTimestampAuthority;
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
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimestampE2ETest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PostgreSQLContainer postgres;
    private FakeTimestampAuthority tsa;

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        tsa = FakeTimestampAuthority.start();
    }

    @AfterEach
    void stop() {
        tsa.close();
        postgres.stop();
    }

    private StandaloneLedger ledger(Path directory, String... extra) {
        String[] args = new String[extra.length + 2];
        args[0] = "--ledger.timestamp.url=" + tsa.url();
        args[1] = "--ledger.timestamp.certificates=" + FakeTimestampAuthority.certificates(directory);
        System.arraycopy(extra, 0, args, 2, extra.length);
        return StandaloneLedger.start(postgres, freePort(), SigningKeys.generate(), args);
    }

    private static void record(LedgerClient client) {
        client.recordEvidence(Map.of("agentId", "invoice-agent", "principalId", "alice", "action", "tools/call",
                "target", "read_invoice", "outcome", "SUCCEEDED"));
    }

    @Test
    void everyCheckpointIsTimestampedAndThePackageProvesIt(@TempDir Path directory) throws IOException {
        try (StandaloneLedger ledger = ledger(directory)) {
            LedgerClient client = ledger.client();
            record(client);

            JsonNode checkpoint = client.post("/api/v1/checkpoints").json();

            TimestampToken token = TimestampToken.parse(checkpoint.path("timestamp").asString());
            assertThat(token.genTime()).isBetween(Instant.parse(checkpoint.path("createdAt").asString()).minusSeconds(5),
                    Instant.now().plusSeconds(5));
            assertThat(client.get("/api/v1/checkpoints/latest").json().path("timestamp").asString())
                    .isEqualTo(checkpoint.path("timestamp").asString());
            JsonNode authority = client.get("/public/v1/timestamp-authority").json();
            assertThat(authority.path("url").asString()).isEqualTo(tsa.url());
            assertThat(authority.path("certificates")).hasSize(2);

            JsonNode pkg = client.post("/api/v1/packages", LedgerClient.json(Map.of("agentId", "invoice-agent")))
                    .json();
            String key = client.get("/api/v1/keys").json().get(0).path("publicKey").asString();
            List<java.security.cert.X509Certificate> chain = TimestampToken.readCertificates(
                    Files.readAllBytes(FakeTimestampAuthority.certificates(directory)));
            PackageReport report = PackageVerifier.verify(pkg, key, null, List.of(), 0,
                    new PackageVerifier.TimestampPolicy(chain, true));
            assertThat(report.valid()).as(report.problems().toString()).isTrue();
            assertThat(report.timestampedAt()).isEqualTo(token.genTime().toString());
            assertThat(report.timestampAuthority()).isEqualTo("CN=Test TSA,O=Nexusphere Test");

            Path file = directory.resolve("package.json");
            Files.write(file, JSON.writeValueAsBytes(pkg));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = VerifierCli.run(new String[]{"--public-key", key, "--timestamp-certificates",
                    FakeTimestampAuthority.certificates(directory).toString(), "--require-timestamp",
                    file.toString()}, new PrintStream(out), System.err);
            assertThat(exit).isZero();
            assertThat(out.toString()).contains("Timestamp   : " + token.genTime() + " by CN=Test TSA");

            ((tools.jackson.databind.node.ObjectNode) pkg.path("checkpoint")).put("createdAt",
                    "2020-01-01T00:00:00Z");
            assertThat(PackageVerifier.verify(pkg, key, null, List.of(), 0,
                    new PackageVerifier.TimestampPolicy(chain, true)).problems())
                    .anyMatch(problem -> problem.contains("timestamp of checkpoint") && problem.contains("different data"));
        }
    }

    @Test
    void aCheckpointIsTimestampedLaterWhenTheAuthorityIsDown(@TempDir Path directory) {
        try (StandaloneLedger ledger = ledger(directory)) {
            LedgerClient client = ledger.client();
            record(client);
            tsa.failing(true);

            JsonNode unstamped = client.post("/api/v1/checkpoints").json();

            assertThat(unstamped.path("timestamp").isNull()).isTrue();
            tsa.failing(false);
            JsonNode stamped = client.post("/api/v1/checkpoints").json();
            assertThat(stamped.path("sequence").asLong()).isEqualTo(unstamped.path("sequence").asLong());
            assertThat(stamped.path("timestamp").isString()).isTrue();
            assertThat(stamped.path("timestampedAt").isString()).isTrue();
            JsonNode pkg = client.post("/api/v1/packages", LedgerClient.json(Map.of("agentId", "invoice-agent")))
                    .json();
            assertThat(PackageVerifier.verify(pkg, null, null, List.of(), 0,
                    new PackageVerifier.TimestampPolicy(List.of(), true)).problems())
                    .noneMatch(problem -> problem.contains("timestamp"));
        }
    }

    @Test
    void theProfilesDecideWhichAuthorityIsAccepted(@TempDir Path directory) throws IOException {
        Path profiles = Files.createDirectory(directory.resolve("profiles"));
        Files.writeString(profiles.resolve("tsa.json"), """
                {"id": "tsa", "version": 1, "name": "TSA", "timestampAuthorities": ["https://tsa.example/tsr"]}
                """);
        assertThatThrownBy(() -> ledger(directory, "--ledger.compliance.profiles=tsa",
                "--ledger.compliance.profile-directory=" + profiles)).hasStackTraceContaining(
                "is not accepted by the compliance profiles");
        assertThatThrownBy(() -> StandaloneLedger.start(postgres, freePort(), SigningKeys.generate(),
                "--ledger.signing.unendorsed-rotation=true", "--ledger.compliance.profiles=tsa",
                "--ledger.compliance.profile-directory=" + profiles))
                .hasStackTraceContaining("LEDGER_TIMESTAMP_URL");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
