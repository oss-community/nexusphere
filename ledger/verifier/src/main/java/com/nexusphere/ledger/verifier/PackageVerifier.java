package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.ChainVerification;
import com.nexusphere.ledger.chain.ChainVerifier;
import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.EvidenceLink;
import com.nexusphere.ledger.chain.EvidenceStatement;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.KeyRevocation;
import com.nexusphere.ledger.chain.KeyRotation;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.LogReceipt;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.chain.TrustedKeys;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class PackageVerifier {

    public static final String FORMAT = "nexusphere-ledger/package/v1";

    private PackageVerifier() {
    }

    public static PackageReport verify(JsonNode pkg, String pinnedPublicKey) {
        return verify(pkg, pinnedPublicKey, List.of(), 0);
    }

    public static PackageReport verify(JsonNode pkg, String pinnedPublicKey, List<NoteKey> witnessKeys,
                                       int requiredWitnesses) {
        return verify(pkg, pinnedPublicKey, null, witnessKeys, requiredWitnesses);
    }

    public static PackageReport verify(JsonNode pkg, String pinnedPublicKey, JsonNode keyList,
                                       List<NoteKey> witnessKeys, int requiredWitnesses) {
        List<String> problems = new ArrayList<>();
        if (!FORMAT.equals(text(pkg, "format"))) {
            problems.add("unknown package format " + text(pkg, "format"));
            return report(false, null, null, null, 0, null, 0, 0, 0, pkg, LogResult.NONE, List.of(), problems);
        }
        SignedCheckpoint checkpoint;
        SignedCheckpoint anchor;
        try {
            checkpoint = checkpoint(pkg.path("checkpoint"));
            anchor = pkg.path("anchor").isObject() ? checkpoint(pkg.path("anchor")) : null;
        } catch (RuntimeException e) {
            problems.add("the checkpoints cannot be read: " + e.getMessage());
            return report(false, null, null, null, 0, null, 0, 0, 0, pkg, LogResult.NONE, List.of(), problems);
        }
        TrustedKeys trusted = keys(pkg, keyList, pinnedPublicKey, problems);
        Map<String, SigningKeys.PublicKeyInfo> keys = trusted == null ? null : byId(trusted.all());
        boolean pinned = pinnedPublicKey != null;
        SigningKeys.PublicKeyInfo key = keys == null ? null : key(keys, checkpoint, pinned, problems);
        if (key != null && !checkpoint.verify(key)) {
            problems.add("the signature of checkpoint " + checkpoint.checkpoint().sequence() + " is not valid");
        }
        SigningKeys.PublicKeyInfo anchorKey = keys == null || anchor == null ? null
                : key(keys, anchor, pinned, problems);
        if (anchorKey != null && !anchor.verify(anchorKey)) {
            problems.add("the signature of anchor checkpoint " + anchor.checkpoint().sequence() + " is not valid");
        }
        long first = anchor == null ? 1 : anchor.checkpoint().sequence() + 1;
        ChainVerifier chain = new ChainVerifier(first, anchor == null ? Hashes.GENESIS : anchor.checkpoint().headHash());
        long disclosed = 0;
        String agentId = nullable(pkg.path("scope"), "agentId");
        String principalId = nullable(pkg.path("scope"), "principalId");
        long from = pkg.path("scope").path("fromSequence").asLong(1);
        long to = pkg.path("scope").path("toSequence").asLong(Long.MAX_VALUE);
        for (JsonNode node : pkg.path("links")) {
            EvidenceLink link = new EvidenceLink(node.path("sequence").asLong(), text(node, "previousHash"),
                    text(node, "contentHash"), text(node, "hash"));
            if (node.path("entry").isObject()) {
                disclosed++;
                String mismatch = disclosedMismatch(node.path("entry"), link, agentId, principalId, from, to);
                if (mismatch != null) {
                    problems.add("sequence " + link.sequence() + ": " + mismatch);
                    break;
                }
            }
            if (!chain.accept(link)) {
                break;
            }
        }
        ChainVerification result = chain.result();
        if (!result.valid()) {
            problems.add("sequence " + result.failedSequence() + ": " + result.failure());
        } else if (problems.isEmpty() && (result.lastSequence() != checkpoint.checkpoint().sequence()
                || !result.lastHash().equals(checkpoint.checkpoint().headHash()))) {
            problems.add("the chain ends at sequence " + result.lastSequence()
                    + " and does not reach the signed head of checkpoint " + checkpoint.checkpoint().sequence());
        }
        if (disclosed == 0) {
            problems.add("the package discloses no evidence");
        }
        LogResult log = keys == null ? LogResult.NONE
                : log(pkg, keys.values(), checkpoint.checkpoint().sequence(), witnessKeys, requiredWitnesses, problems);
        Set<String> used = new LinkedHashSet<>();
        used.add(checkpoint.checkpoint().keyId());
        if (anchor != null) {
            used.add(anchor.checkpoint().keyId());
        }
        used.addAll(log.signers());
        List<String> revoked = trusted == null ? List.of()
                : revoked(trusted, used, log, Math.max(1, requiredWitnesses), problems);
        return report(problems.isEmpty(), checkpoint.checkpoint().keyId(), pinnedKeyId(pinnedPublicKey),
                anchor == null ? null : anchor.checkpoint().sequence(), checkpoint.checkpoint().sequence(),
                checkpoint.checkpoint().createdAt().toString(), first, result.checkedEntries(), disclosed, pkg, log,
                revoked, problems);
    }

    private static List<String> revoked(TrustedKeys trusted, Set<String> used, LogResult log, int required,
                                        List<String> problems) {
        List<String> revoked = new ArrayList<>();
        for (String keyId : used) {
            KeyRevocation revocation = trusted.revocation(keyId).orElse(null);
            if (revocation == null) {
                continue;
            }
            revoked.add(keyId);
            long before = log.cosignedAt().values().stream()
                    .filter(time -> time < revocation.compromisedAt().getEpochSecond()).count();
            if (before < required) {
                problems.add("key " + keyId + " was revoked as compromised from " + revocation.compromisedAt()
                        + ", and " + before + " of the " + required
                        + " required witness cosignatures prove that the log checkpoint was made before then");
            }
        }
        return revoked;
    }

    private static Map<String, SigningKeys.PublicKeyInfo> byId(Collection<SigningKeys.PublicKeyInfo> keys) {
        Map<String, SigningKeys.PublicKeyInfo> byId = new LinkedHashMap<>();
        keys.forEach(key -> byId.put(key.keyId(), key));
        return byId;
    }

    private record LogResult(String origin, Long size, long proven, long receipted, List<String> witnesses,
                             Map<String, Long> cosignedAt, Set<String> signers) {

        static final LogResult NONE = new LogResult(null, null, 0, 0, List.of(), Map.of(), Set.of());
    }

    private static LogResult log(JsonNode pkg, Collection<SigningKeys.PublicKeyInfo> keys, long size,
                                 List<NoteKey> witnessKeys, int requiredWitnesses, List<String> problems) {
        JsonNode log = pkg.path("log");
        if (!log.isObject()) {
            if (requiredWitnesses > 0) {
                problems.add("the package has no log checkpoint for witnesses to cosign");
            }
            return LogResult.NONE;
        }
        LogCheckpoint.Note note;
        try {
            note = LogCheckpoint.parse(text(log, "checkpoint"));
        } catch (RuntimeException e) {
            problems.add("the log checkpoint cannot be read: " + e.getMessage());
            return LogResult.NONE;
        }
        LogCheckpoint checkpoint = note.checkpoint();
        Set<String> signers = new LinkedHashSet<>();
        keys.stream().filter(key -> note.signedBy(new NoteKey(checkpoint.origin(), NoteKey.ED25519, key.publicKey())))
                .forEach(key -> signers.add(key.keyId()));
        if (signers.isEmpty()) {
            problems.add("the log checkpoint is not signed by a trusted ledger key");
        }
        if (checkpoint.size() != size) {
            problems.add("the log checkpoint covers " + checkpoint.size() + " entries, not the " + size
                    + " of the signed checkpoint");
        }
        Map<Long, List<byte[]>> proofs = new HashMap<>();
        Map<Long, JsonNode> scitt = new HashMap<>();
        for (JsonNode proof : log.path("proofs")) {
            List<byte[]> hashes = new ArrayList<>();
            proof.path("hashes").forEach(hash -> hashes.add(Base64.getDecoder().decode(hash.asString())));
            proofs.put(proof.path("sequence").asLong(), hashes);
            scitt.put(proof.path("sequence").asLong(), proof);
        }
        long receipted = 0;
        long proven = 0;
        for (JsonNode node : pkg.path("links")) {
            if (!node.path("entry").isObject()) {
                continue;
            }
            long sequence = node.path("sequence").asLong();
            List<byte[]> proof = proofs.get(sequence);
            byte[] leaf = MerkleTree.leafHash(HexFormat.of().parseHex(text(node, "hash")));
            if (proof == null || !MerkleTree.verifyInclusion(leaf, sequence - 1, checkpoint.size(), proof,
                    checkpoint.root())) {
                problems.add("sequence " + sequence + " is not proven to be in the log checkpoint");
            } else {
                proven++;
            }
            JsonNode statement = scitt.get(sequence);
            if (statement != null && statement.has("statement")) {
                String problem = scittProblem(statement, link(node), keys, checkpoint, signers);
                if (problem == null) {
                    receipted++;
                } else {
                    problems.add("sequence " + sequence + " " + problem);
                }
            }
        }
        Map<String, Long> cosignedAt = new LinkedHashMap<>();
        witnessKeys.forEach(key -> note.cosignedBy(key).ifPresent(time -> cosignedAt.put(key.name(), time)));
        List<String> cosigned = List.copyOf(cosignedAt.keySet());
        if (cosigned.size() < requiredWitnesses) {
            problems.add("the log checkpoint is cosigned by " + cosigned.size() + " of the " + requiredWitnesses
                    + " required witnesses");
        }
        return new LogResult(checkpoint.origin(), checkpoint.size(), proven, receipted, cosigned, cosignedAt,
                signers);
    }

    private static String scittProblem(JsonNode signed, EvidenceLink link,
                                       Collection<SigningKeys.PublicKeyInfo> keys, LogCheckpoint checkpoint,
                                       Set<String> signers) {
        EvidenceStatement statement;
        LogReceipt receipt;
        try {
            statement = EvidenceStatement.parse(Base64.getDecoder().decode(text(signed, "statement")));
            receipt = LogReceipt.parse(Base64.getDecoder().decode(text(signed, "receipt")));
        } catch (RuntimeException e) {
            return "has a statement or receipt that cannot be read: " + e.getMessage();
        }
        SigningKeys.PublicKeyInfo statementKey = keys.stream()
                .filter(key -> key.keyId().equals(statement.keyId())).findFirst().orElse(null);
        SigningKeys.PublicKeyInfo receiptKey = keys.stream()
                .filter(key -> key.keyId().equals(receipt.keyId())).findFirst().orElse(null);
        if (statementKey == null || !statement.verify(statementKey.publicKey())) {
            return "has a statement that is not signed by a trusted ledger key";
        }
        signers.add(statementKey.keyId());
        if (!statement.describes(link)) {
            return "has a statement for different evidence";
        }
        if (receiptKey == null || !receipt.verify(statement.leafHash(), receiptKey.publicKey())) {
            return "has a receipt that does not prove its statement with a trusted ledger key";
        }
        signers.add(receiptKey.keyId());
        if (receipt.treeSize() != checkpoint.size()
                || !Arrays.equals(receipt.root(statement.leafHash()).orElseThrow(), checkpoint.root())) {
            return "has a receipt for a different log checkpoint";
        }
        return null;
    }

    private static EvidenceLink link(JsonNode node) {
        return new EvidenceLink(node.path("sequence").asLong(), text(node, "previousHash"), text(node, "contentHash"),
                text(node, "hash"));
    }

    public static EvidenceEntry entry(JsonNode node) {
        TreeMap<String, String> attributes = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : node.path("attributes").properties()) {
            attributes.put(e.getKey(), e.getValue().asString());
        }
        return new EvidenceEntry(UUID.fromString(text(node, "id")), node.path("sequence").asLong(),
                Instant.parse(text(node, "occurredAt")), Instant.parse(text(node, "recordedAt")),
                nullable(node, "agentId"), nullable(node, "principalId"), nullable(node, "action"),
                nullable(node, "target"), nullable(node, "decision"), nullable(node, "reason"),
                nullable(node, "delegationId"), nullable(node, "inputHash"), nullable(node, "outputHash"),
                nullable(node, "outcome"), nullable(node, "correlationId"), attributes,
                text(node, "previousHash"), text(node, "hash"));
    }

    private static String disclosedMismatch(JsonNode node, EvidenceLink link, String agentId, String principalId,
                                            long from, long to) {
        EvidenceEntry entry;
        try {
            entry = entry(node);
        } catch (RuntimeException e) {
            return "the disclosed entry cannot be read";
        }
        if (entry.sequence() != link.sequence() || !entry.previousHash().equals(link.previousHash())
                || !entry.hash().equals(link.hash())) {
            return "the disclosed entry does not belong to its link";
        }
        if (!entry.contentHash().equals(link.contentHash())) {
            return "the disclosed entry does not match its content hash";
        }
        if (entry.sequence() < from || entry.sequence() > to
                || agentId != null && !agentId.equals(entry.agentId())
                || principalId != null && !principalId.equals(entry.principalId())) {
            return "the disclosed entry is outside the scope of the package";
        }
        return null;
    }

    private static TrustedKeys keys(JsonNode pkg, JsonNode keyList, String pinned, List<String> problems) {
        Map<String, SigningKeys.PublicKeyInfo> listed = new LinkedHashMap<>();
        List<KeyRotation> rotations = new ArrayList<>();
        List<KeyRevocation> revocations = new ArrayList<>();
        try {
            List<JsonNode> nodes = new ArrayList<>();
            pkg.path("keys").forEach(nodes::add);
            if (keyList != null) {
                keyList.forEach(nodes::add);
            }
            for (JsonNode node : nodes) {
                String keyId = text(node, "keyId");
                SigningKeys.PublicKeyInfo key = SigningKeys.PublicKeyInfo.of(
                        SigningKeys.decodePublic(text(node, "publicKey")));
                if (!key.keyId().equals(keyId)) {
                    problems.add("the key " + keyId + " in the package does not match its key ID");
                    return null;
                }
                listed.put(keyId, key);
                JsonNode rotation = node.path("rotation");
                if (rotation.isObject()) {
                    rotations.add(new KeyRotation(keyId, key.encoded(), text(rotation, "previousKeyId"),
                            Instant.parse(text(node, "activatedAt")), text(rotation, "keySignature"),
                            nullable(rotation, "previousKeySignature")));
                }
                JsonNode revocation = node.path("revocation");
                if (revocation.isObject()) {
                    revocations.add(new KeyRevocation(keyId, Instant.parse(text(revocation, "compromisedAt")),
                            Instant.parse(text(revocation, "revokedAt")), text(revocation, "reason"),
                            text(revocation, "revokerKeyId"), text(revocation, "signature")));
                }
            }
            if (pinned == null) {
                return TrustedKeys.listed(listed.values(), revocations);
            }
            SigningKeys.PublicKeyInfo pinnedKey = SigningKeys.PublicKeyInfo.of(SigningKeys.decodePublic(pinned));
            return TrustedKeys.from(pinnedKey, listed.values(), rotations, revocations);
        } catch (RuntimeException e) {
            problems.add("the public keys cannot be read: " + e.getMessage());
            return null;
        }
    }

    private static SigningKeys.PublicKeyInfo key(Map<String, SigningKeys.PublicKeyInfo> keys,
                                                 SignedCheckpoint signed, boolean pinned, List<String> problems) {
        String keyId = signed.checkpoint().keyId();
        SigningKeys.PublicKeyInfo key = keys.get(keyId);
        if (key == null) {
            problems.add(pinned
                    ? "checkpoint " + signed.checkpoint().sequence() + " is signed with key " + keyId
                    + ", which the pinned key does not reach through signed key rotations"
                    : "the package has no key " + keyId);
        }
        return key;
    }

    private static String pinnedKeyId(String pinned) {
        try {
            return pinned == null ? null : SigningKeys.keyIdOf(SigningKeys.decodePublic(pinned));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static SignedCheckpoint checkpoint(JsonNode node) {
        return new SignedCheckpoint(new Checkpoint(node.path("sequence").asLong(), text(node, "headHash"),
                Instant.parse(text(node, "createdAt")), text(node, "keyId")), text(node, "signature"));
    }

    private static PackageReport report(boolean valid, String keyId, String pinnedKeyId, Long anchor, long checkpoint,
                                        String createdAt, long first, long checked, long disclosed, JsonNode pkg,
                                        LogResult log, List<String> revoked, List<String> problems) {
        return new PackageReport(valid, keyId, pinnedKeyId, anchor, checkpoint, createdAt, first, checked, disclosed,
                nullable(pkg.path("scope"), "agentId"), nullable(pkg.path("scope"), "principalId"), log.origin(),
                log.size(), log.proven(), log.receipted(), log.witnesses(), List.copyOf(revoked), List.copyOf(problems));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value.asString();
    }

    private static String nullable(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() ? value.asString() : null;
    }
}
