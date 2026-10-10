package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.IndexRoot;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.chain.SparseMerkleTree;
import com.nexusphere.ledger.mandate.TrackRecordClaims;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class IndexProofVerifier {

    public static final String FORMAT = "nexusphere-ledger/index-proof/v1";
    private static final HexFormat HEX = HexFormat.of();

    private IndexProofVerifier() {
    }

    public record IndexCheck(boolean valid, Map<String, Object> term, boolean present, Long count,
                             Long firstSequence, Long lastSequence, Long sequence, String headHash, String keyId,
                             List<String> problems) {
    }

    public static IndexCheck verify(JsonNode proof, String pinnedPublicKey, JsonNode keyList) {
        return verify(proof, pinnedPublicKey, keyList, null);
    }

    public static IndexCheck verify(JsonNode proof, String pinnedPublicKey, JsonNode keyList, JsonNode pkg) {
        List<String> problems = new ArrayList<>();
        JsonNode index = proof.path("index");
        Long sequence = index.path("sequence").isIntegralNumber() ? index.path("sequence").asLong() : null;
        String headHash = index.path("headHash").asString(null);
        String keyId = index.path("keyId").asString(null);
        JsonNode leaf = proof.path("leaf");
        boolean present = leaf.isObject();
        Long count = present && leaf.path("count").isIntegralNumber() ? leaf.path("count").asLong() : null;
        Long first = present && leaf.path("firstSequence").isIntegralNumber()
                ? leaf.path("firstSequence").asLong() : null;
        Long last = present && leaf.path("lastSequence").isIntegralNumber() ? leaf.path("lastSequence").asLong()
                : null;
        Map<String, Object> term = new LinkedHashMap<>();
        if (!FORMAT.equals(proof.path("format").asString(null))) {
            problems.add("unknown index proof format " + proof.path("format").asString(null));
            return new IndexCheck(false, term, present, count, first, last, sequence, headHash, keyId, problems);
        }
        try {
            Map<String, Object> requested = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : proof.path("term").properties()) {
                requested.put(field.getKey(), field.getValue().isString() ? field.getValue().asString() : null);
            }
            term = IndexRoot.term(requested);
        } catch (IllegalArgumentException e) {
            problems.add("the term is not valid: " + e.getMessage());
            return new IndexCheck(false, term, present, count, first, last, sequence, headHash, keyId, problems);
        }
        byte[] key = SparseMerkleTree.key(term);
        if (!HEX.formatHex(key).equals(proof.path("key").asString(""))) {
            problems.add("the key does not belong to the term");
        }
        IndexRoot root;
        try {
            if (!IndexRoot.FORMAT.equals(index.path("format").asString(null)) || sequence == null) {
                throw new IllegalArgumentException("unknown index format " + index.path("format").asString(null));
            }
            root = new IndexRoot(sequence, headHash, index.path("root").asString(null),
                    Instant.parse(index.path("createdAt").asString("")), Objects.requireNonNull(keyId, "keyId"));
        } catch (RuntimeException e) {
            problems.add("the index cannot be read: " + e.getMessage());
            return new IndexCheck(false, term, present, count, first, last, sequence, headHash, keyId, problems);
        }
        Map<String, SigningKeys.PublicKeyInfo> trusted = PackageVerifier.trustedKeys(proof, keyList, pinnedPublicKey,
                problems);
        SigningKeys.PublicKeyInfo signing = trusted.get(keyId);
        if (signing == null) {
            problems.add("the index is signed with key " + keyId + ", which the proof does not trust");
        } else if (revoked(proof, keyList, keyId)) {
            problems.add("the index is signed with revoked key " + keyId);
        } else if (!SigningKeys.verify(signing.publicKey(), root.signedBytes(), index.path("signature")
                .asString(""))) {
            problems.add("the signature of the index is not valid");
        }
        try {
            byte[] valueHash = null;
            if (present) {
                if (count == null || first == null || last == null || count < 1 || first < 1 || first > last
                        || last > sequence || count > last - first + 1) {
                    throw new IllegalArgumentException("the leaf is not a valid count with its first and last"
                            + " sequence");
                }
                valueHash = SparseMerkleTree.valueHash(IndexRoot.value(count, first, last));
            } else if (!leaf.isNull() && !leaf.isMissingNode()) {
                throw new IllegalArgumentException("the leaf must be an object or null");
            }
            List<byte[]> siblings = new ArrayList<>();
            for (JsonNode sibling : proof.path("siblings")) {
                siblings.add(hash(sibling.asString("")));
            }
            JsonNode other = proof.path("otherLeaf");
            SparseMerkleTree.Proof path = other.isObject()
                    ? new SparseMerkleTree.Proof(siblings, hash(other.path("key").asString("")),
                    hash(other.path("valueHash").asString("")))
                    : new SparseMerkleTree.Proof(siblings, null, null);
            if (!Arrays.equals(SparseMerkleTree.rootFrom(key, valueHash, path), HEX.parseHex(root.root()))) {
                problems.add("the proof does not lead to the signed root");
            }
        } catch (IllegalArgumentException e) {
            problems.add("the proof cannot be read: " + e.getMessage());
        }
        if (pkg != null) {
            JsonNode checkpoint = pkg.path("checkpoint");
            if (sequence != checkpoint.path("sequence").asLong(-1)
                    || !Objects.equals(headHash, checkpoint.path("headHash").asString(null))) {
                problems.add("the index is for another checkpoint than the package");
            }
        }
        return new IndexCheck(problems.isEmpty(), term, present, count, first, last, sequence, headHash, keyId,
                problems);
    }

    private static byte[] hash(String hex) {
        if (!Hashes.isSha256(hex)) {
            throw new IllegalArgumentException("a hash must be a lowercase hex SHA-256");
        }
        return HEX.parseHex(hex);
    }

    private static boolean revoked(JsonNode proof, JsonNode keyList, String keyId) {
        List<JsonNode> nodes = new ArrayList<>();
        proof.path("keys").forEach(nodes::add);
        if (keyList != null) {
            keyList.forEach(nodes::add);
        }
        return nodes.stream().anyMatch(node -> keyId.equals(node.path("keyId").asString(null))
                && node.path("revocation").isObject());
    }

    public static List<String> matchTrackRecord(TrackRecordClaims claims, JsonNode proof, IndexCheck check) {
        List<String> problems = new ArrayList<>();
        if (!"agent".equals(check.term().get("type")) || !claims.agentId().equals(check.term().get("agentId"))) {
            problems.add("The index proof is not about the agent " + claims.agentId());
        }
        if (check.sequence() == null || check.sequence() != claims.checkpointSequence()
                || !claims.headHash().equals(check.headHash())
                || !claims.indexRoot().equals(proof.path("index").path("root").asString(null))) {
            problems.add("The index proof is for another checkpoint or index root than the track record");
        }
        long count = check.present() ? check.count() : 0;
        if (count != claims.total() || !java.util.Objects.equals(check.present() ? check.firstSequence() : null,
                claims.firstSequence()) || !java.util.Objects.equals(check.present() ? check.lastSequence() : null,
                claims.lastSequence())) {
            problems.add("The track record claims " + claims.total() + " entries but the index proves " + count);
        }
        return problems;
    }
}
