package com.nexusphere.ledger.e2e.transparency;

import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.FakeRekor;
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
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RekorE2ETest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PostgreSQLContainer postgres;
    private FakeRekor rekor;

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        rekor = FakeRekor.start();
    }

    @AfterEach
    void stop() {
        rekor.close();
        postgres.stop();
    }

    private StandaloneLedger ledger() {
        return StandaloneLedger.start(postgres, freePort(), SigningKeys.generate(),
                "--ledger.rekor.url=" + rekor.url(), "--ledger.rekor.key=" + rekor.verifierKey());
    }

    private static void record(LedgerClient client) {
        client.recordEvidence(Map.of("agentId", "invoice-agent", "principalId", "alice", "action", "tools/call",
                "target", "read_invoice", "outcome", "SUCCEEDED"));
    }

    @Test
    void everyCheckpointIsAnchoredInRekorAndThePackageProvesIt(@TempDir Path directory) throws IOException {
        try (StandaloneLedger ledger = ledger()) {
            LedgerClient client = ledger.client();
            record(client);

            JsonNode checkpoint = client.post("/api/v1/checkpoints").json();

            assertThat(checkpoint.path("rekorLogIndex").asLong()).isZero();
            assertThat(client.get("/public/v1/rekor").json().path("key").asString()).isEqualTo(rekor.verifierKey());
            JsonNode pkg = client.post("/api/v1/packages", LedgerClient.json(Map.of("agentId", "invoice-agent")))
                    .json();
            assertThat(pkg.path("checkpoint").path("rekor").path("canonicalizedBody").isString()).isTrue();
            String key = client.get("/api/v1/keys").json().get(0).path("publicKey").asString();
            PackageVerifier.RekorPolicy pinned = new PackageVerifier.RekorPolicy(
                    List.of(NoteKey.parse(rekor.verifierKey())), true);
            PackageReport report = PackageVerifier.verify(pkg, key, null, List.of(), 0,
                    PackageVerifier.TimestampPolicy.NONE, pinned);
            assertThat(report.valid()).as(report.problems().toString()).isTrue();
            assertThat(report.rekorLogIndex()).isZero();
            assertThat(report.rekorOrigin()).isEqualTo(FakeRekor.ORIGIN);

            Path file = directory.resolve("package.json");
            Files.write(file, JSON.writeValueAsBytes(pkg));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = VerifierCli.run(new String[]{"--public-key", key, "--rekor-key", rekor.verifierKey(),
                    "--require-rekor", file.toString()}, new PrintStream(out), System.err);
            assertThat(exit).isZero();
            assertThat(out.toString()).contains("Rekor       : entry 0 in rekor.test (pinned)");

            ((ObjectNode) pkg.path("checkpoint")).remove("rekor");
            assertThat(PackageVerifier.verify(pkg, key, null, List.of(), 0, PackageVerifier.TimestampPolicy.NONE,
                    pinned).problems()).contains("checkpoint " + checkpoint.path("sequence").asLong()
                    + " is not anchored in Rekor");
        }
    }

    @Test
    void aCheckpointIsAnchoredLaterWhenRekorIsDown() {
        try (StandaloneLedger ledger = ledger()) {
            LedgerClient client = ledger.client();
            record(client);
            rekor.failing(true);

            JsonNode unanchored = client.post("/api/v1/checkpoints").json();

            assertThat(unanchored.path("rekorLogIndex").isNull()).isTrue();
            rekor.failing(false);
            JsonNode anchored = client.post("/api/v1/checkpoints").json();
            assertThat(anchored.path("sequence").asLong()).isEqualTo(unanchored.path("sequence").asLong());
            assertThat(anchored.path("rekorLogIndex").isNumber()).isTrue();
            assertThat(client.get("/api/v1/checkpoints/latest").json().path("rekorLogIndex").asLong())
                    .isEqualTo(anchored.path("rekorLogIndex").asLong());
        }
    }

    @Test
    void theLedgerNeedsTheVerifierKeyOfTheRekorLog() {
        assertThatThrownBy(() -> StandaloneLedger.start(postgres, freePort(), SigningKeys.generate(),
                "--ledger.rekor.url=" + rekor.url())).hasStackTraceContaining("LEDGER_REKOR_KEY");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
