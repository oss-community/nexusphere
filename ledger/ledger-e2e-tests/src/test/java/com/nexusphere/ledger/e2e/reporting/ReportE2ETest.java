package com.nexusphere.ledger.e2e.reporting;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.verifier.ReportVerifier;
import com.nexusphere.ledger.verifier.VerifierCli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportE2ETest {

    private static final String HASH = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

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

    private StandaloneLedger ledger(String... extra) {
        return StandaloneLedger.start(postgres, freePort(), keys, extra);
    }

    private static void record(LedgerClient client) {
        client.recordEvidence(Map.of("agentId", "payments-agent", "principalId", "alice", "action", "tools/call",
                "target", "bank/pay_invoice", "inputHash", HASH, "delegationId", "grant-1", "decision", "ALLOW",
                "outcome", "SUCCEEDED"));
        client.recordEvidence(Map.of("agentId", "payments-agent", "principalId", "alice", "action", "tools/call",
                "target", "bank/pay_invoice", "inputHash", HASH, "decision", "DENY", "outcome", "DENIED",
                "reason", "the grant allows one payment"));
        client.recordEvidence(Map.of("agentId", "payments-agent", "principalId", "alice", "action", "tools/call",
                "target", "erp/read_invoice", "inputHash", HASH, "delegationId", "grant-1", "decision", "ALLOW",
                "outcome", "FAILED"));
        client.post("/api/v1/checkpoints");
    }

    private static String scope() {
        return LedgerClient.json(Map.of("agentId", "payments-agent"));
    }

    private static JsonNode section(JsonNode report, String id) {
        for (JsonNode section : report.path("sections")) {
            if (id.equals(section.path("id").asString())) {
                return section;
            }
        }
        throw new AssertionError("no section " + id);
    }

    @Test
    void everyReportOfTheProfilesIsSignedAndBoundToItsPackage() {
        try (StandaloneLedger ledger = ledger("--ledger.compliance.profiles=eu,us",
                "--ledger.compliance.region=eea")) {
            LedgerClient client = ledger.client();
            record(client);
            String key = SigningKeys.encode(keys.getPublic());

            List<String> offered = client.get("/api/v1/reports").json().findValuesAsString("id");

            assertThat(offered).containsExactly("dispute", "dora-ict-records", "eu-ai-act-logging",
                    "finra-supervision", "sec-17a-4-records");
            JsonNode pkg = client.post("/api/v1/packages", scope()).json();
            for (String id : offered) {
                JsonNode report = client.post("/api/v1/reports/" + id, scope()).json();
                ReportVerifier.ReportCheck check = ReportVerifier.verify(report, pkg, key, null);
                assertThat(check.valid()).as(id + " " + check.problems()).isTrue();
                assertThat(check.entries()).isEqualTo(3);
            }
            JsonNode aiAct = client.post("/api/v1/reports/eu-ai-act-logging", scope()).json();
            assertThat(aiAct.path("profiles").findValuesAsString("id")).containsExactly("eu");
            assertThat(section(aiAct, "art-12-3").path("status").asString()).isEqualTo("SHOWN");
            assertThat(section(aiAct, "art-19-1").path("status").asString()).isEqualTo("SHOWN");
            assertThat(section(aiAct, "art-12-2").path("entries").valueStream().map(JsonNode::asLong).toList())
                    .containsExactly(4L, 5L);
            JsonNode finra = client.post("/api/v1/reports/finra-supervision", scope()).json();
            assertThat(section(finra, "rule-3110-b").path("status").asString()).isEqualTo("PARTLY_SHOWN");
            assertThat(client.post("/api/v1/reports/sec-17a-4-records?format=html", scope()).body())
                    .contains("<h1>SEC Rule 17a-4 records</h1>", "not legal advice");
        }
    }

    @Test
    void onlyTheReportsOfTheActiveProfilesAreOffered() {
        try (StandaloneLedger ledger = ledger()) {
            LedgerClient client = ledger.client();
            record(client);

            assertThat(client.get("/api/v1/reports").json().findValuesAsString("id")).containsExactly("dispute");
            assertThat(client.post("/api/v1/reports/finra-supervision", scope()).status()).isEqualTo(404);
            assertThat(client.post("/api/v1/reports/dispute?format=pdf", scope()).status()).isEqualTo(400);
        }
    }

    @Test
    void aProfileCanNameOnlyTheReportsTheLedgerHasTemplatesFor(@TempDir Path directory) throws IOException {
        Path profiles = Files.createDirectory(directory.resolve("profiles"));
        Files.writeString(profiles.resolve("sox.json"), """
                {"id": "sox", "version": 1, "name": "SOX", "reports": ["dispute", "sox-404"]}
                """);

        assertThatThrownBy(() -> ledger("--ledger.compliance.profiles=sox",
                "--ledger.compliance.profile-directory=" + profiles))
                .hasStackTraceContaining("names the report sox-404, which has no template");
    }

    @Test
    void aDisputePackageHoldsEverythingNeededToCheckIt(@TempDir Path directory) throws IOException {
        try (StandaloneLedger ledger = ledger()) {
            LedgerClient client = ledger.client();
            record(client);

            assertThat(client.post("/api/v1/disputes", scope()).status()).isEqualTo(400);
            byte[] zip = client.postBytes("/api/v1/disputes", LedgerClient.json(Map.of("agentId", "payments-agent",
                    "title", "Invoice 4711 paid twice", "description", "The supplier says it was paid twice.")));

            List<String> names = new ArrayList<>();
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
                for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    names.add(entry.getName());
                }
            }
            assertThat(names).containsExactly("README.txt", "package.json", "report.json", "report.html",
                    "keys.json", "compliance.json");
            Path file = directory.resolve("dispute.zip");
            Files.write(file, zip);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = VerifierCli.run(new String[]{"dispute", "--public-key", SigningKeys.encode(keys.getPublic()),
                    file.toString()}, new PrintStream(out), System.err);
            assertThat(out.toString()).contains("Report      : dispute (Dispute package)",
                    "Evidence    : checkpoint 5, 3 entries");
            assertThat(exit).as(out.toString()).isZero();

            ByteArrayOutputStream other = new ByteArrayOutputStream();
            assertThat(VerifierCli.run(new String[]{"dispute", "--public-key",
                    SigningKeys.encode(SigningKeys.generate().getPublic()), file.toString()},
                    new PrintStream(other), System.err)).isEqualTo(VerifierCli.INVALID);
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
