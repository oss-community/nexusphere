package com.nexusphere.ledger.e2e.transparency;

import com.nexusphere.ledger.chain.EvidenceStatement;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.LogReceipt;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import com.nexusphere.ledger.verifier.VerifierCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScittE2ETest extends LedgerE2ETestBase {

    private String publicKey() {
        return ledger().get("/api/v1/keys").json().get(0).path("publicKey").asString();
    }

    @Test
    void everyEntryHasASignedStatementAndAReceiptFromTheLog() {
        JsonNode recorded = ledger().recordEvidence(toolCall(unique("agent"), "alice", "read_file"));
        String id = recorded.path("id").asString();
        PublicKey key = SigningKeys.decodePublic(publicKey());

        EvidenceStatement statement = EvidenceStatement.parse(ledger().getBytes("/api/v1/evidence/" + id + "/statement"));
        LogReceipt receipt = LogReceipt.parse(ledger().getBytes("/api/v1/evidence/" + id + "/receipt"));
        LogCheckpoint.Note note = LogCheckpoint.parse(anonymous().get("/public/v1/log/checkpoint").body());

        assertThat(statement.verify(key)).isTrue();
        assertThat(statement.issuer()).isEqualTo(ISSUER);
        assertThat(statement.subject()).isEqualTo("urn:uuid:" + id);
        assertThat(statement.describes(LedgerClient.toEntry(recorded).link())).isTrue();
        assertThat(receipt.issuer()).isEqualTo(note.checkpoint().origin());
        assertThat(receipt.verify(statement.leafHash(), key)).isTrue();
        assertThat(receipt.treeSize()).isGreaterThanOrEqualTo(recorded.path("sequence").asLong());
    }

    @Test
    void aReceiptCanBeAskedForAtAnEarlierLogCheckpoint() {
        JsonNode recorded = ledger().recordEvidence(toolCall(unique("agent"), "bob", "search"));
        long size = ledger().post("/api/v1/checkpoints").json().path("sequence").asLong();
        ledger().recordEvidence(toolCall(unique("agent"), "bob", "search"));
        ledger().post("/api/v1/checkpoints");
        String id = recorded.path("id").asString();

        LogReceipt receipt = LogReceipt.parse(ledger().getBytes("/api/v1/evidence/" + id + "/receipt?treeSize=" + size));
        LedgerClient.Response tooSmall = ledger().get("/api/v1/evidence/" + id + "/receipt?treeSize="
                + (recorded.path("sequence").asLong() - 1));

        assertThat(receipt.treeSize()).isEqualTo(size);
        assertThat(tooSmall.status()).isEqualTo(400);
    }

    @Test
    void statementsAndReceiptsBelongToTheEvidenceOwner() {
        RegisteredAgent owner = registerAgent("acme");
        RegisteredAgent other = registerAgent("acme");
        JsonNode recorded = owner.client().recordEvidence(toolCall(owner.agentId(), "carol", "pay"));
        String id = recorded.path("id").asString();

        assertThat(owner.client().get("/api/v1/evidence/" + id + "/statement").status()).isEqualTo(200);
        assertThat(other.client().get("/api/v1/evidence/" + id + "/statement").status()).isEqualTo(404);
        assertThat(other.client().get("/api/v1/evidence/" + id + "/receipt").status()).isEqualTo(404);
        assertThat(anonymous().get("/api/v1/evidence/" + id + "/statement").status()).isEqualTo(401);
    }

    @Test
    void aPackageCarriesAStatementAndReceiptForEveryDisclosedEntry(@TempDir Path dir) throws Exception {
        String agent = unique("agent");
        JsonNode recorded = ledger().recordEvidence(toolCall(agent, "dave", "read_file"));
        ledger().recordEvidence(toolCall(agent, "dave", "write_file"));
        JsonNode pkg = ledger().post("/api/v1/packages", LedgerClient.json(Map.of("agentId", agent))).json();

        PackageReport report = PackageVerifier.verify(pkg, publicKey());
        ObjectNode swapped = (ObjectNode) pkg.deepCopy();
        JsonNode proofs = swapped.path("log").path("proofs");
        String first = proofs.get(0).path("statement").asString();
        ((ObjectNode) proofs.get(0)).put("statement", proofs.get(1).path("statement").asString());
        ((ObjectNode) proofs.get(1)).put("statement", first);
        Path statement = dir.resolve("statement.cose");
        Path receipt = dir.resolve("receipt.cose");
        Files.write(statement, Base64.getDecoder().decode(pkg.path("log").path("proofs").get(0).path("statement")
                .asString()));
        Files.write(receipt, Base64.getDecoder().decode(pkg.path("log").path("proofs").get(0).path("receipt")
                .asString()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = VerifierCli.run(new String[]{"statement", "--public-key", publicKey(), "--receipt",
                receipt.toString(), statement.toString()}, new PrintStream(out), System.err);

        assertThat(report.valid()).isTrue();
        assertThat(report.receiptedEntries()).isEqualTo(2);
        assertThat(PackageVerifier.verify(swapped, publicKey()).problems())
                .anyMatch(problem -> problem.contains("statement for different evidence"));
        assertThat(exit).isEqualTo(VerifierCli.VALID);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Sequence    : "
                + recorded.path("sequence").asLong()).contains("Result: VALID");
    }
}
