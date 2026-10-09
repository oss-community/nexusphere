package com.nexusphere.ledger.e2e.transparency;

import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransparencyLogE2ETest extends LedgerE2ETestBase {

    private LogCheckpoint.Note checkpoint() {
        LedgerClient.Response response = anonymous().get("/public/v1/log/checkpoint");
        assertThat(response.status()).isEqualTo(200);
        return LogCheckpoint.parse(response.body());
    }

    private NoteKey logKey() {
        return NoteKey.parse(anonymous().get("/public/v1/log/key").body());
    }

    private static List<byte[]> hashes(JsonNode proof) {
        List<byte[]> hashes = new ArrayList<>();
        proof.path("hashes").forEach(hash -> hashes.add(Base64.getDecoder().decode(hash.asString())));
        return hashes;
    }

    @Test
    void theLogCheckpointIsASignedNoteOverTheMerkleRoot() {
        ledger().recordEvidence(toolCall(unique("agent"), "alice", "read_file"));
        long size = ledger().post("/api/v1/checkpoints").json().path("sequence").asLong();

        LogCheckpoint.Note note = checkpoint();
        NoteKey key = logKey();

        assertThat(note.checkpoint().origin()).isEqualTo(ISSUER.substring("http://".length()));
        assertThat(note.checkpoint().size()).isGreaterThanOrEqualTo(size);
        assertThat(key.name()).isEqualTo(note.checkpoint().origin());
        assertThat(note.signedBy(key)).isTrue();
    }

    @Test
    void anEntryIsProvenInTheSignedTree() {
        JsonNode entry = ledger().recordEvidence(toolCall(unique("agent"), "alice", "send_email"));
        ledger().post("/api/v1/checkpoints");
        LogCheckpoint.Note note = checkpoint();
        long sequence = entry.path("sequence").asLong();

        JsonNode proof = ledger().get("/api/v1/log/proofs/inclusion?sequence=" + sequence + "&treeSize="
                + note.checkpoint().size()).json();
        byte[] leaf = MerkleTree.leafHash(HexFormat.of().parseHex(entry.path("hash").asString()));

        assertThat(Base64.getDecoder().decode(proof.path("rootHash").asString()))
                .isEqualTo(note.checkpoint().root());
        assertThat(MerkleTree.verifyInclusion(leaf, sequence - 1, note.checkpoint().size(), hashes(proof),
                note.checkpoint().root())).isTrue();
        assertThat(anonymous().get("/api/v1/log/proofs/inclusion?sequence=" + sequence).status()).isEqualTo(401);
    }

    @Test
    void aLaterTreeIsProvenToExtendAnEarlierOne() {
        ledger().recordEvidence(toolCall(unique("agent"), "bob", "first"));
        ledger().post("/api/v1/checkpoints");
        LogCheckpoint first = checkpoint().checkpoint();
        ledger().recordEvidence(toolCall(unique("agent"), "bob", "second"));
        ledger().recordEvidence(toolCall(unique("agent"), "bob", "third"));
        ledger().post("/api/v1/checkpoints");
        LogCheckpoint second = checkpoint().checkpoint();

        JsonNode proof = ledger().get("/api/v1/log/proofs/consistency?firstSize=" + first.size() + "&secondSize="
                + second.size()).json();

        assertThat(second.size()).isGreaterThan(first.size());
        assertThat(MerkleTree.verifyConsistency(first.size(), second.size(), hashes(proof), first.root(),
                second.root())).isTrue();
    }

    @Test
    void aPackageProvesItsEntriesInTheLog() {
        String agent = unique("agent");
        ledger().recordEvidence(toolCall(agent, "carol", "read_file"));
        ledger().recordEvidence(toolCall(agent, "carol", "write_file"));
        String publicKey = ledger().get("/api/v1/keys").json().get(0).path("publicKey").asString();

        JsonNode pkg = ledger().post("/api/v1/packages", LedgerClient.json(Map.of("agentId", agent))).json();
        PackageReport report = PackageVerifier.verify(pkg, publicKey);

        assertThat(report.valid()).isTrue();
        assertThat(report.provenEntries()).isEqualTo(2);
        assertThat(report.logTreeSize()).isEqualTo(pkg.path("checkpoint").path("sequence").asLong());

        ObjectNode forged = (ObjectNode) pkg.deepCopy();
        ArrayNode hashes = (ArrayNode) forged.path("log").path("proofs").get(0).path("hashes");
        if (hashes.isEmpty()) {
            hashes.add(Base64.getEncoder().encodeToString(new byte[32]));
        } else {
            hashes.set(0, Base64.getEncoder().encodeToString(new byte[32]));
        }
        PackageReport broken = PackageVerifier.verify(forged, publicKey);

        assertThat(broken.valid()).isFalse();
        assertThat(broken.problems()).anyMatch(problem -> problem.contains("is not proven to be in the log"));
        assertThat(PackageVerifier.verify(pkg, publicKey, List.of(), 1).problems())
                .anyMatch(problem -> problem.contains("required witnesses"));
    }

    @Test
    void verificationChecksTheMerkleTreeAndLogCheckpoints() {
        ledger().recordEvidence(toolCall(unique("agent"), "dave", "read_file"));
        ledger().post("/api/v1/checkpoints");

        JsonNode report = ledger().get("/api/v1/verification").json();

        assertThat(report.path("valid").asBoolean()).isTrue();
    }

    @Test
    void thisLedgerWitnessesNoLogUnlessConfigured() {
        LedgerClient.Response response = anonymous().postText("/public/v1/witness/add-checkpoint", "old 0\n\n");

        assertThat(response.status()).isEqualTo(404);
    }
}
