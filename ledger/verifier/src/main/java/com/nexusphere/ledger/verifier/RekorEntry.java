package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

public final class RekorEntry {

    public static final String KIND = "hashedrekord";
    public static final String API_VERSION = "0.0.2";
    public static final String KEY_DETAILS = "PKIX_ED25519_PH";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RekorEntry() {
    }

    public record Verification(boolean valid, Long logIndex, String origin, boolean pinned, String problem) {

        static Verification invalid(String problem) {
            return new Verification(false, null, null, false, problem);
        }
    }

    public static String request(byte[] signedBytes, byte[] signature, PublicKey key) {
        Base64.Encoder base64 = Base64.getEncoder();
        return JSON.writeValueAsString(JSON.createObjectNode().set("hashedRekordRequestV002", JSON.createObjectNode()
                .put("digest", base64.encodeToString(Hashes.sha512Bytes(signedBytes)))
                .set("signature", JSON.createObjectNode().put("content", base64.encodeToString(signature))
                        .set("verifier", JSON.createObjectNode().put("keyDetails", KEY_DETAILS)
                                .set("publicKey", JSON.createObjectNode()
                                        .put("rawBytes", base64.encodeToString(key.getEncoded())))))));
    }

    public static Verification verify(JsonNode entry, byte[] signedBytes, PublicKey ledgerKey,
                                      Collection<NoteKey> rekorKeys) {
        long logIndex;
        byte[] body;
        long treeSize;
        byte[] root;
        List<byte[]> hashes = new ArrayList<>();
        LogCheckpoint.Note note;
        JsonNode spec;
        try {
            logIndex = Long.parseLong(text(entry, "logIndex"));
            body = Base64.getDecoder().decode(text(entry, "canonicalizedBody"));
            JsonNode proof = entry.path("inclusionProof");
            if (Long.parseLong(text(proof, "logIndex")) != logIndex) {
                return Verification.invalid("the inclusion proof is for another entry");
            }
            treeSize = Long.parseLong(text(proof, "treeSize"));
            root = Base64.getDecoder().decode(text(proof, "rootHash"));
            for (JsonNode hash : proof.path("hashes")) {
                hashes.add(Base64.getDecoder().decode(hash.asString()));
            }
            note = LogCheckpoint.parse(text(proof.path("checkpoint"), "envelope"));
            JsonNode parsed = JSON.readTree(body);
            if (!KIND.equals(parsed.path("kind").asString(null))
                    || !API_VERSION.equals(parsed.path("apiVersion").asString(null))) {
                return Verification.invalid("the entry is not a hashedrekord 0.0.2");
            }
            spec = parsed.path("spec").path("hashedRekordV002");
        } catch (JacksonException | IllegalArgumentException e) {
            return Verification.invalid("the entry cannot be read: " + e.getMessage());
        }
        JsonNode data = spec.path("data");
        byte[] digest = decode(data.path("digest"));
        if (!"SHA2_512".equals(data.path("algorithm").asString(null)) || digest == null
                || !MessageDigest.isEqual(digest, Hashes.sha512Bytes(signedBytes))) {
            return Verification.invalid("the entry is for different data");
        }
        JsonNode verifier = spec.path("signature").path("verifier");
        byte[] key = decode(verifier.path("publicKey").path("rawBytes"));
        if (!KEY_DETAILS.equals(verifier.path("keyDetails").asString(null)) || key == null
                || !MessageDigest.isEqual(key, ledgerKey.getEncoded())) {
            return Verification.invalid("the entry is signed by another key");
        }
        if (!MerkleTree.verifyInclusion(MerkleTree.leafHash(body), logIndex, treeSize, hashes, root)) {
            return Verification.invalid("the entry is not proven in the log");
        }
        if (note.checkpoint().size() != treeSize || !MessageDigest.isEqual(note.checkpoint().root(), root)) {
            return Verification.invalid("the log checkpoint does not match the inclusion proof");
        }
        boolean pinned = rekorKeys != null && !rekorKeys.isEmpty();
        if (pinned && rekorKeys.stream().noneMatch(note::signedBy)) {
            return Verification.invalid("the log checkpoint is not signed by a pinned Rekor key");
        }
        return new Verification(true, logIndex, note.checkpoint().origin(), pinned, null);
    }

    private static byte[] decode(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(node.asString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asString();
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value.asString();
    }
}
