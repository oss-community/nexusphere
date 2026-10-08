package com.nexusphere.ledger.e2e.evidence;

import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import com.nexusphere.ledger.verifier.VerifierCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PackageE2ETest extends LedgerE2ETestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private String publicKey() {
        return ledger().get("/api/v1/keys").json().get(0).path("publicKey").asString();
    }

    private JsonNode export(Map<String, Object> request) {
        LedgerClient.Response response = ledger().post("/api/v1/packages", LedgerClient.json(request));
        assertThat(response.status()).isEqualTo(200);
        return response.json();
    }

    @Test
    void aPackageDisclosesOnlyTheRequestedEvidenceAndVerifiesOffline(@TempDir Path dir) throws Exception {
        String agent = unique("agent");
        ledger().recordEvidence(toolCall(agent, "alice", "read_file"));
        ledger().recordEvidence(toolCall(unique("other"), "bob", "secret_tool"));
        ledger().recordEvidence(toolCall(agent, "alice", "send_email"));

        JsonNode pkg = export(Map.of("agentId", agent));
        Path file = dir.resolve("package.json");
        Files.write(file, JSON.writeValueAsBytes(pkg));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = VerifierCli.run(new String[]{"--public-key", publicKey(), file.toString()},
                new PrintStream(out), System.err);

        assertThat(pkg.path("format").asString()).isEqualTo("nexusphere-ledger/package/v1");
        assertThat(pkg.path("disclosed").asLong()).isEqualTo(2);
        pkg.path("links").forEach(link -> {
            if (link.path("entry").isObject()) {
                assertThat(link.path("entry").path("agentId").asString()).isEqualTo(agent);
            }
        });
        assertThat(pkg.toString()).doesNotContain("secret_tool");
        assertThat(exit).isEqualTo(VerifierCli.VALID);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Result: VALID").contains("(pinned)");
    }

    @Test
    void aPackageStartsAtTheLastCheckpointBeforeTheEvidence() {
        ledger().recordEvidence(toolCall(unique("agent"), "carol", "before"));
        long anchor = ledger().post("/api/v1/checkpoints").json().path("sequence").asLong();
        String agent = unique("agent");
        JsonNode recorded = ledger().recordEvidence(toolCall(agent, "carol", "after"));

        JsonNode pkg = export(Map.of("agentId", agent));
        PackageReport report = PackageVerifier.verify(pkg, publicKey());

        assertThat(pkg.path("anchor").path("sequence").asLong()).isEqualTo(anchor);
        assertThat(pkg.path("links").get(0).path("sequence").asLong()).isEqualTo(anchor + 1);
        assertThat(pkg.path("checkpoint").path("sequence").asLong())
                .isGreaterThanOrEqualTo(recorded.path("sequence").asLong());
        assertThat(report.valid()).isTrue();
        assertThat(report.anchorSequence()).isEqualTo(anchor);
    }

    @Test
    void anyChangeToAPackageIsDetected() {
        String agent = unique("agent");
        ledger().recordEvidence(toolCall(agent, "dave", "pay_invoice"));
        ledger().recordEvidence(toolCall(unique("other"), "erin", "search"));
        ledger().recordEvidence(toolCall(agent, "dave", "send_receipt"));
        JsonNode genuine = export(Map.of("agentId", agent));

        ObjectNode changedEntry = (ObjectNode) genuine.deepCopy();
        for (JsonNode link : changedEntry.path("links")) {
            if (link.path("entry").isObject()) {
                ((ObjectNode) link.path("entry")).put("target", "pay_someone_else");
                break;
            }
        }
        ObjectNode changedLink = (ObjectNode) genuine.deepCopy();
        for (JsonNode link : changedLink.path("links")) {
            if (!link.path("entry").isObject()) {
                ((ObjectNode) link).put("contentHash", "f".repeat(64));
                break;
            }
        }
        ObjectNode dropped = (ObjectNode) genuine.deepCopy();
        ((ArrayNode) dropped.path("links")).remove(0);
        ObjectNode forgedCheckpoint = (ObjectNode) genuine.deepCopy();
        ((ObjectNode) forgedCheckpoint.path("checkpoint")).put("headHash", "e".repeat(64));

        assertThat(PackageVerifier.verify(genuine, publicKey()).valid()).isTrue();
        assertThat(PackageVerifier.verify(changedEntry, publicKey()).valid()).isFalse();
        assertThat(PackageVerifier.verify(changedLink, publicKey()).valid()).isFalse();
        assertThat(PackageVerifier.verify(dropped, publicKey()).valid()).isFalse();
        assertThat(PackageVerifier.verify(forgedCheckpoint, publicKey()).problems())
                .anyMatch(problem -> problem.contains("signature"));
    }

    @Test
    void packagesAreForTheOperatorAndNeedMatchingEvidence() {
        RegisteredAgent agent = registerAgent("acme");

        LedgerClient.Response byAgent = agent.client().post("/api/v1/packages", LedgerClient.json(Map.of()));
        LedgerClient.Response nothing = ledger().post("/api/v1/packages",
                LedgerClient.json(Map.of("agentId", unique("nobody"))));
        LedgerClient.Response invalid = ledger().post("/api/v1/packages",
                LedgerClient.json(Map.of("fromSequence", 0)));

        assertThat(byAgent.status()).isEqualTo(403);
        assertThat(nothing.status()).isEqualTo(409);
        assertThat(nothing.json().path("code").asString()).isEqualTo("NOTHING_SELECTED");
        assertThat(invalid.status()).isEqualTo(400);
    }

    @Test
    void theOperatorCanHaveTheLedgerVerifyAPackage() {
        String agent = unique("agent");
        ledger().recordEvidence(toolCall(agent, "frank", "read_file"));
        JsonNode genuine = export(Map.of("agentId", agent));
        ObjectNode changed = (ObjectNode) genuine.deepCopy();
        for (JsonNode link : changed.path("links")) {
            if (link.path("entry").isObject()) {
                ((ObjectNode) link.path("entry")).put("target", "delete_file");
            }
        }
        String key = URLEncoder.encode(publicKey(), StandardCharsets.UTF_8);

        JsonNode valid = ledger().post("/api/v1/packages/verify?publicKey=" + key, genuine.toString()).json();
        JsonNode invalid = ledger().post("/api/v1/packages/verify?publicKey=" + key, changed.toString()).json();
        LedgerClient.Response byAgent = registerAgent("acme").client().post("/api/v1/packages/verify",
                genuine.toString());

        assertThat(valid.path("valid").asBoolean()).isTrue();
        assertThat(valid.path("pinnedKeyId").isString()).isTrue();
        assertThat(invalid.path("valid").asBoolean()).isFalse();
        assertThat(invalid.path("problems").get(0).asString()).isNotBlank();
        assertThat(byAgent.status()).isEqualTo(403);
    }
}
