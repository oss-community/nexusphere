package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PackageVerifierTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final KeyPair KEYS = SigningKeys.generate();
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");

    private static ObjectNode pkg(int length, int disclosed) {
        List<EvidenceEntry> entries = new ArrayList<>();
        String previous = Hashes.GENESIS;
        for (int i = 1; i <= length; i++) {
            EvidenceEntry entry = new EvidenceEntry(UUID.randomUUID(), i, NOW, NOW, i == disclosed ? "agent-a" : "agent-b",
                    "alice", "tools/call", "search", "ALLOW", null, null, null, null, "SUCCEEDED", null,
                    new TreeMap<>(), previous, null).sealed();
            entries.add(entry);
            previous = entry.hash();
        }
        String keyId = SigningKeys.keyIdOf(KEYS.getPublic());
        Checkpoint checkpoint = new Checkpoint(length, previous, NOW, keyId);
        ObjectNode root = JSON.createObjectNode();
        root.put("format", PackageVerifier.FORMAT);
        root.putObject("scope").put("agentId", "agent-a").put("fromSequence", 1).put("toSequence", length);
        root.putArray("keys").addObject().put("keyId", keyId).put("algorithm", "Ed25519")
                .put("publicKey", SigningKeys.encode(KEYS.getPublic()));
        root.putNull("anchor");
        root.putObject("checkpoint").put("sequence", checkpoint.sequence()).put("headHash", checkpoint.headHash())
                .put("createdAt", NOW.toString()).put("keyId", keyId)
                .put("signature", SigningKeys.sign(KEYS.getPrivate(), checkpoint.signedBytes()));
        ArrayNode links = root.putArray("links");
        for (EvidenceEntry e : entries) {
            ObjectNode link = links.addObject().put("sequence", e.sequence()).put("previousHash", e.previousHash())
                    .put("contentHash", e.contentHash()).put("hash", e.hash());
            if (e.sequence() == disclosed) {
                link.set("entry", JSON.valueToTree(entryJson(e)));
            }
        }
        return root;
    }

    private static ObjectNode entryJson(EvidenceEntry e) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", e.id().toString()).put("sequence", e.sequence())
                .put("occurredAt", "2026-10-07T08:00:00.000000Z").put("recordedAt", "2026-10-07T08:00:00.000000Z")
                .put("agentId", e.agentId()).put("principalId", e.principalId()).put("action", e.action())
                .put("target", e.target()).put("decision", e.decision()).put("outcome", e.outcome())
                .put("previousHash", e.previousHash()).put("hash", e.hash());
        node.putObject("attributes");
        return node;
    }

    @Test
    void aGenuinePackageIsValid() {
        PackageReport report = PackageVerifier.verify(pkg(4, 2), SigningKeys.encode(KEYS.getPublic()));

        assertThat(report.problems()).isEmpty();
        assertThat(report.valid()).isTrue();
        assertThat(report.keyPinned()).isTrue();
        assertThat(report.checkedLinks()).isEqualTo(4);
        assertThat(report.disclosedEntries()).isEqualTo(1);
    }

    @Test
    void aChangedDisclosedEntryIsInvalid() {
        ObjectNode pkg = pkg(4, 2);
        ((ObjectNode) pkg.path("links").get(1).path("entry")).put("target", "delete_repository");

        PackageReport report = PackageVerifier.verify(pkg, null);

        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("sequence 2") && p.contains("content hash"));
    }

    @Test
    void aDroppedLinkIsInvalid() {
        ObjectNode pkg = pkg(4, 2);
        ((ArrayNode) pkg.path("links")).remove(2);

        assertThat(PackageVerifier.verify(pkg, null).problems()).anyMatch(p -> p.contains("expected sequence 3"));
    }

    @Test
    void anotherKeyIsRejectedWhenTheLedgerKeyIsPinned() {
        String other = SigningKeys.encode(SigningKeys.generate().getPublic());

        PackageReport report = PackageVerifier.verify(pkg(3, 1), other);

        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("pinned key"));
    }

    @Test
    void anEntryOutsideTheScopeIsInvalid() {
        ObjectNode pkg = pkg(3, 1);
        ((ObjectNode) pkg.path("scope")).put("agentId", "agent-z");

        assertThat(PackageVerifier.verify(pkg, null).problems()).anyMatch(p -> p.contains("outside the scope"));
    }

    @Test
    void theCommandLineReportsTheResultAndExitCode(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("package.json");
        Files.write(file, JSON.writeValueAsBytes(pkg(3, 3)));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int valid = VerifierCli.run(new String[]{file.toString()}, new PrintStream(out), new PrintStream(err));
        int usage = VerifierCli.run(new String[]{}, new PrintStream(out), new PrintStream(err));
        int missing = VerifierCli.run(new String[]{dir.resolve("none.json").toString()}, new PrintStream(out),
                new PrintStream(err));

        assertThat(valid).isEqualTo(VerifierCli.VALID);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Result: VALID").contains("taken from the package");
        assertThat(usage).isEqualTo(VerifierCli.USAGE);
        assertThat(missing).isEqualTo(VerifierCli.USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Usage:");
    }
}
