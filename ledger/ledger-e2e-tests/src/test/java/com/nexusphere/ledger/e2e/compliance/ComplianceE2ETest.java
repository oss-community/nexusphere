package com.nexusphere.ledger.e2e.compliance;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceE2ETest {

    private final KeyPair keys = SigningKeys.generate();
    private final int port = freePort();
    private PostgreSQLContainer postgres;

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
    }

    @AfterEach
    void stop() {
        postgres.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private StandaloneLedger ledger(String... extra) {
        return StandaloneLedger.start(postgres, port, keys, extra);
    }

    private static List<JsonNode> activations(LedgerClient client) {
        List<JsonNode> found = new ArrayList<>();
        client.get("/api/v1/evidence?agentId=ledger&limit=500").json().path("items").forEach(item -> {
            if ("compliance/activate".equals(item.path("action").asString())) {
                found.add(item);
            }
        });
        return found;
    }

    @Test
    void theActiveProfilesAreEnforcedSignedAndRecorded() {
        try (StandaloneLedger ledger = ledger("--ledger.compliance.profiles=eu,us",
                "--ledger.compliance.region=eea")) {
            LedgerClient client = ledger.client();

            JsonNode compliance = client.get("/public/v1/compliance").json();
            assertThat(compliance.path("region").asString()).isEqualTo("eea");
            assertThat(compliance.path("profiles").findValuesAsString("id")).containsExactly("eu", "us");
            assertThat(compliance.path("effective").path("residency").get(0).asString()).isEqualTo("eea");
            assertThat(compliance.path("effective").path("retention").get(0).path("minimum").asString())
                    .isEqualTo("P6Y");
            assertThat(compliance.path("effective").path("erasure").path("deadline").asString()).isEqualTo("P1M");

            LedgerClient.Response missing = client.post("/api/v1/evidence", LedgerClient.json(Map.of(
                    "agentId", "agent-a", "principalId", "alice", "action", "tools/call", "outcome", "SUCCEEDED")));
            assertThat(missing.status()).isEqualTo(400);
            assertThat(missing.json().path("details").path("fields").path("target").asString())
                    .isEqualTo("is required by the compliance profile eu, us");
            assertThat(missing.json().path("details").path("fields").path("inputHash").asString())
                    .isEqualTo("is required by the compliance profile eu");

            client.recordEvidence(Map.of("agentId", "agent-a", "principalId", "alice", "action", "tools/call",
                    "target", "search", "inputHash", "a".repeat(64), "decision", "ALLOW", "outcome", "SUCCEEDED"));
            JsonNode checkpoint = client.post("/api/v1/checkpoints").json();
            assertThat(checkpoint.path("format").asString()).isEqualTo(Checkpoint.FORMAT_WITH_PROFILES);
            assertThat(checkpoint.path("profiles").findValuesAsString("digest"))
                    .isEqualTo(compliance.path("profiles").findValuesAsString("digest"));

            PackageReport report = PackageVerifier.verify(client.post("/api/v1/packages", "{}").json(),
                    SigningKeys.encode(keys.getPublic()));
            assertThat(report.problems()).isEmpty();
            assertThat(report.complianceProfiles()).extracting(Checkpoint.Profile::id).containsExactly("eu", "us");

            List<JsonNode> activations = activations(client);
            assertThat(activations).singleElement().satisfies(entry -> {
                assertThat(entry.path("target").asString()).isEqualTo("eu,us");
                assertThat(entry.path("attributes").path("region").asString()).isEqualTo("eea");
            });
        }

        try (StandaloneLedger ledger = ledger("--ledger.compliance.profiles=us,eu",
                "--ledger.compliance.region=eea")) {
            assertThat(activations(ledger.client())).hasSize(1);
        }

        try (StandaloneLedger ledger = ledger()) {
            List<JsonNode> activations = activations(ledger.client());
            assertThat(activations).hasSize(2);
            assertThat(activations.getLast().path("target").asString()).isEqualTo("baseline");
            assertThat(ledger.client().post("/api/v1/checkpoints").json().path("profiles")
                    .findValuesAsString("id")).containsExactly("baseline");
        }
    }

    @Test
    void aLedgerOutsideTheAllowedRegionDoesNotStart() {
        assertThatThrownBy(() -> ledger("--ledger.compliance.profiles=eu", "--ledger.compliance.region=us"))
                .hasRootCauseMessage("The region us is not allowed by the compliance profiles eu, which allow [eea]");
        assertThatThrownBy(() -> ledger("--ledger.compliance.profiles=eu"))
                .rootCause().hasMessageContaining("set LEDGER_COMPLIANCE_REGION");
        assertThatThrownBy(() -> ledger("--ledger.compliance.profiles=uk"))
                .rootCause().hasMessageContaining("There is no compliance profile uk");
    }
}
