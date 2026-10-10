package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.KeyRevocation;
import com.nexusphere.ledger.chain.KeyRotation;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.Signer;
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
import java.util.Base64;
import java.util.HexFormat;
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
        assertThat(report.pinnedKeyId()).isEqualTo(SigningKeys.keyIdOf(KEYS.getPublic()));
        assertThat(report.checkedLinks()).isEqualTo(4);
        assertThat(report.disclosedEntries()).isEqualTo(1);
    }

    @Test
    void theSignedComplianceProfilesAreReportedAndCannotBeChanged() {
        ObjectNode pkg = pkg(3, 1);
        String keyId = SigningKeys.keyIdOf(KEYS.getPublic());
        String digest = Hashes.sha256(new byte[]{7});
        Checkpoint checkpoint = new Checkpoint(3, pkg.path("checkpoint").path("headHash").asString(), NOW, keyId,
                List.of(new Checkpoint.Profile("eu", digest)));
        ObjectNode node = (ObjectNode) pkg.path("checkpoint");
        node.put("format", checkpoint.format()).put("signature",
                SigningKeys.sign(KEYS.getPrivate(), checkpoint.signedBytes()));
        node.putArray("profiles").addObject().put("id", "eu").put("digest", digest);

        PackageReport report = PackageVerifier.verify(pkg, SigningKeys.encode(KEYS.getPublic()));

        assertThat(report.problems()).isEmpty();
        assertThat(report.complianceProfiles()).containsExactly(new Checkpoint.Profile("eu", digest));

        ((ObjectNode) node.path("profiles").get(0)).put("id", "us");

        assertThat(PackageVerifier.verify(pkg, SigningKeys.encode(KEYS.getPublic())).problems())
                .anyMatch(p -> p.contains("signature of checkpoint 3"));
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

    private static ObjectNode rotated(KeyPair next, boolean endorsed) {
        ObjectNode pkg = pkg(3, 1);
        SigningKeys.PublicKeyInfo key = SigningKeys.PublicKeyInfo.of(next.getPublic());
        KeyRotation rotation = KeyRotation.issue(key, next.getPrivate(), SigningKeys.keyIdOf(KEYS.getPublic()),
                endorsed ? KEYS.getPrivate() : null, NOW);
        ObjectNode listed = ((ArrayNode) pkg.path("keys")).addObject().put("keyId", key.keyId())
                .put("algorithm", "Ed25519").put("publicKey", key.encoded()).put("activatedAt", NOW.toString());
        listed.putObject("rotation").put("previousKeyId", rotation.previousKeyId())
                .put("keySignature", rotation.keySignature())
                .put("previousKeySignature", rotation.previousKeySignature());
        ObjectNode checkpoint = (ObjectNode) pkg.path("checkpoint");
        Checkpoint signed = new Checkpoint(checkpoint.path("sequence").asLong(), checkpoint.path("headHash").asString(),
                NOW, key.keyId());
        checkpoint.put("keyId", key.keyId()).put("signature", SigningKeys.sign(next.getPrivate(), signed.signedBytes()));
        return pkg;
    }

    @Test
    void aCheckpointOfAnEndorsedNewKeyIsValidWithTheOldKeyPinned() {
        KeyPair next = SigningKeys.generate();

        PackageReport report = PackageVerifier.verify(rotated(next, true), SigningKeys.encode(KEYS.getPublic()));

        assertThat(report.problems()).isEmpty();
        assertThat(report.keyId()).isEqualTo(SigningKeys.keyIdOf(next.getPublic()));
        assertThat(report.pinnedKeyId()).isEqualTo(SigningKeys.keyIdOf(KEYS.getPublic()));
    }

    @Test
    void aCheckpointOfAnUnendorsedNewKeyNeedsTheNewKeyPinned() {
        KeyPair next = SigningKeys.generate();

        PackageReport old = PackageVerifier.verify(rotated(next, false), SigningKeys.encode(KEYS.getPublic()));
        PackageReport current = PackageVerifier.verify(rotated(next, false), SigningKeys.encode(next.getPublic()));

        assertThat(old.valid()).isFalse();
        assertThat(old.problems()).anyMatch(p -> p.contains("does not reach"));
        assertThat(current.problems()).isEmpty();
    }

    private static final KeyPair NEXT = SigningKeys.generate();
    private static final KeyPair WITNESS = SigningKeys.generate();
    private static final NoteKey WITNESS_KEY = new NoteKey("witness.example", NoteKey.COSIGNATURE,
            WITNESS.getPublic());
    private static final Instant COMPROMISED = Instant.parse("2026-10-08T00:00:00Z");

    private static ObjectNode witnessed(ObjectNode pkg, Instant cosignedAt) {
        List<byte[]> leaves = new ArrayList<>();
        pkg.path("links").forEach(link -> leaves.add(MerkleTree.leafHash(
                HexFormat.of().parseHex(link.path("hash").asString()))));
        MerkleTree.Subtrees tree = MerkleTree.of(leaves);
        LogCheckpoint checkpoint = new LogCheckpoint("ledger.example", leaves.size(),
                MerkleTree.root(tree, leaves.size()));
        LogCheckpoint.Note note = checkpoint.sign(new NoteKey("ledger.example", NoteKey.ED25519, KEYS.getPublic()),
                KEYS.getPrivate());
        note = note.with(LogCheckpoint.cosign(note.body(), WITNESS_KEY, WITNESS.getPrivate(),
                cosignedAt.getEpochSecond()));
        ObjectNode log = pkg.putObject("log").put("checkpoint", note.text());
        ArrayNode proofs = log.putArray("proofs");
        pkg.path("links").forEach(link -> {
            if (link.path("entry").isObject()) {
                long sequence = link.path("sequence").asLong();
                ArrayNode hashes = proofs.addObject().put("sequence", sequence).putArray("hashes");
                MerkleTree.inclusionProof(tree, sequence - 1, leaves.size())
                        .forEach(hash -> hashes.add(Base64.getEncoder().encodeToString(hash)));
            }
        });
        return pkg;
    }

    private static ArrayNode keyList(KeyPair revoker) {
        String keyId = SigningKeys.keyIdOf(KEYS.getPublic());
        SigningKeys.PublicKeyInfo next = SigningKeys.PublicKeyInfo.of(NEXT.getPublic());
        Instant activatedAt = COMPROMISED.plusSeconds(3600);
        KeyRotation rotation = KeyRotation.issue(next, NEXT.getPrivate(), keyId, KEYS.getPrivate(), activatedAt);
        KeyRevocation revocation = KeyRevocation.issue(keyId, COMPROMISED, activatedAt, "key leaked",
                SigningKeys.keyIdOf(revoker.getPublic()), Signer.of(revoker.getPrivate()));
        ArrayNode keys = JSON.createArrayNode();
        keys.addObject().put("keyId", keyId).put("publicKey", SigningKeys.encode(KEYS.getPublic()))
                .put("status", "REVOKED").putObject("revocation").put("format", KeyRevocation.FORMAT)
                .put("compromisedAt", COMPROMISED.toString()).put("revokedAt", activatedAt.toString())
                .put("reason", revocation.reason()).put("revokerKeyId", revocation.revokerKeyId())
                .put("signature", revocation.signature());
        ObjectNode listed = keys.addObject().put("keyId", next.keyId()).put("publicKey", next.encoded())
                .put("status", "ACTIVE").put("activatedAt", activatedAt.toString());
        listed.putObject("rotation").put("previousKeyId", keyId).put("keySignature", rotation.keySignature())
                .put("previousKeySignature", rotation.previousKeySignature());
        return keys;
    }

    private static PackageReport verifyRevoked(ObjectNode pkg, KeyPair revoker, List<NoteKey> witnesses) {
        return PackageVerifier.verify(pkg, SigningKeys.encode(NEXT.getPublic()), keyList(revoker), witnesses, 0);
    }

    @Test
    void aRevokedKeyIsValidWhenWitnessesProveTheLogBeforeTheCompromise() {
        PackageReport report = verifyRevoked(witnessed(pkg(4, 2), COMPROMISED.minusSeconds(60)), NEXT,
                List.of(WITNESS_KEY));

        assertThat(report.problems()).isEmpty();
        assertThat(report.revokedKeys()).containsExactly(SigningKeys.keyIdOf(KEYS.getPublic()));
        assertThat(report.witnesses()).containsExactly("witness.example");
    }

    @Test
    void aRevokedKeyIsInvalidWhenTheWitnessesCosignedAfterTheCompromise() {
        PackageReport report = verifyRevoked(witnessed(pkg(4, 2), COMPROMISED.plusSeconds(60)), NEXT,
                List.of(WITNESS_KEY));

        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("revoked as compromised from " + COMPROMISED)
                && p.contains("0 of the 1 required witness cosignatures"));
    }

    @Test
    void aRevokedKeyIsInvalidWithoutWitnesses() {
        assertThat(verifyRevoked(pkg(4, 2), NEXT, List.of()).valid()).isFalse();
        assertThat(verifyRevoked(witnessed(pkg(4, 2), COMPROMISED.minusSeconds(60)), NEXT, List.of()).valid())
                .isFalse();
    }

    @Test
    void aRevocationByAnUntrustedKeyIsIgnored() {
        PackageReport report = verifyRevoked(pkg(4, 2), SigningKeys.generate(), List.of());

        assertThat(report.problems()).isEmpty();
        assertThat(report.revokedKeys()).isEmpty();
    }

    @Test
    void theRevocationInTheKeyListAppliesWhenThePackageOmitsIt() {
        PackageReport withoutList = PackageVerifier.verify(pkg(4, 2), SigningKeys.encode(KEYS.getPublic()));
        PackageReport withList = PackageVerifier.verify(pkg(4, 2), SigningKeys.encode(KEYS.getPublic()),
                keyList(NEXT), List.of(), 0);

        assertThat(withoutList.valid()).isTrue();
        assertThat(withList.valid()).isFalse();
        assertThat(withList.revokedKeys()).containsExactly(SigningKeys.keyIdOf(KEYS.getPublic()));
    }

    @Test
    void aKeyEndorsedByARevokedKeyAfterItsCompromiseIsNotReached() {
        ObjectNode pkg = rotated(NEXT, true);
        ((ArrayNode) pkg.path("keys")).remove(1);

        PackageReport report = PackageVerifier.verify(pkg, SigningKeys.encode(KEYS.getPublic()), keyList(NEXT),
                List.of(), 0);

        assertThat(report.valid()).isFalse();
        assertThat(report.problems()).anyMatch(p -> p.contains("does not reach"));
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
