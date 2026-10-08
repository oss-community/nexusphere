package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.ChainVerification;
import com.nexusphere.ledger.chain.ChainVerifier;
import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.EvidenceLink;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.KeyRotation;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.chain.TrustedKeys;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class PackageVerifier {

    public static final String FORMAT = "nexusphere-ledger/package/v1";

    private PackageVerifier() {
    }

    public static PackageReport verify(JsonNode pkg, String pinnedPublicKey) {
        List<String> problems = new ArrayList<>();
        if (!FORMAT.equals(text(pkg, "format"))) {
            problems.add("unknown package format " + text(pkg, "format"));
            return report(false, null, null, null, 0, null, 0, 0, 0, pkg, problems);
        }
        SignedCheckpoint checkpoint;
        SignedCheckpoint anchor;
        try {
            checkpoint = checkpoint(pkg.path("checkpoint"));
            anchor = pkg.path("anchor").isObject() ? checkpoint(pkg.path("anchor")) : null;
        } catch (RuntimeException e) {
            problems.add("the checkpoints cannot be read: " + e.getMessage());
            return report(false, null, null, null, 0, null, 0, 0, 0, pkg, problems);
        }
        Map<String, SigningKeys.PublicKeyInfo> keys = keys(pkg, pinnedPublicKey, problems);
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
        return report(problems.isEmpty(), checkpoint.checkpoint().keyId(), pinnedKeyId(pinnedPublicKey),
                anchor == null ? null : anchor.checkpoint().sequence(), checkpoint.checkpoint().sequence(),
                checkpoint.checkpoint().createdAt().toString(), first, result.checkedEntries(), disclosed, pkg,
                problems);
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

    private static Map<String, SigningKeys.PublicKeyInfo> keys(JsonNode pkg, String pinned, List<String> problems) {
        Map<String, SigningKeys.PublicKeyInfo> listed = new LinkedHashMap<>();
        List<KeyRotation> rotations = new ArrayList<>();
        try {
            for (JsonNode node : pkg.path("keys")) {
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
            }
            if (pinned == null) {
                return listed;
            }
            SigningKeys.PublicKeyInfo pinnedKey = SigningKeys.PublicKeyInfo.of(SigningKeys.decodePublic(pinned));
            Map<String, SigningKeys.PublicKeyInfo> trusted = new LinkedHashMap<>();
            TrustedKeys.from(pinnedKey, listed.values(), rotations).all()
                    .forEach(key -> trusted.put(key.keyId(), key));
            return trusted;
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
                                        List<String> problems) {
        return new PackageReport(valid, keyId, pinnedKeyId, anchor, checkpoint, createdAt, first, checked, disclosed,
                nullable(pkg.path("scope"), "agentId"), nullable(pkg.path("scope"), "principalId"),
                List.copyOf(problems));
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
