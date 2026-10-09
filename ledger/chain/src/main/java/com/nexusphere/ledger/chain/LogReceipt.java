package com.nexusphere.ledger.chain;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record LogReceipt(CoseSign1 message, String issuer, long treeSize, long leafIndex, List<byte[]> path) {

    public static final long RFC9162_SHA256 = 1;
    public static final long INCLUSION_PROOFS = -1;

    public static byte[] sign(String issuer, String keyId, long treeSize, long leafIndex, List<byte[]> path,
                              byte[] root, PrivateKey key) {
        Map<Object, Object> header = Map.of(
                CoseSign1.ALG, CoseSign1.EDDSA,
                CoseSign1.KID, keyId.getBytes(StandardCharsets.US_ASCII),
                CoseSign1.VDS, RFC9162_SHA256,
                CoseSign1.CWT_CLAIMS, Map.of(CoseSign1.ISS, issuer));
        byte[] proof = Cbor.encode(List.of(treeSize, leafIndex, List.copyOf(path)));
        Map<Object, Object> unprotected = Map.of(CoseSign1.VDP, Map.of(INCLUSION_PROOFS, List.of(proof)));
        return CoseSign1.sign(header, unprotected, root, true, key);
    }

    public static LogReceipt parse(byte[] data) {
        CoseSign1 message = CoseSign1.parse(data);
        if (!Long.valueOf(RFC9162_SHA256).equals(message.protectedHeader().get(CoseSign1.VDS))
                || !(message.claims().get(CoseSign1.ISS) instanceof String issuer)
                || !(message.unprotected().get(CoseSign1.VDP) instanceof Map<?, ?> proofs)
                || !(proofs.get(INCLUSION_PROOFS) instanceof List<?> inclusion) || inclusion.size() != 1
                || !(inclusion.getFirst() instanceof byte[] encoded)
                || !(Cbor.decode(encoded) instanceof List<?> proof) || proof.size() != 3
                || !(proof.get(0) instanceof Long treeSize) || !(proof.get(1) instanceof Long leafIndex)
                || !(proof.get(2) instanceof List<?> hashes)) {
            throw new IllegalArgumentException("Not an RFC 9162 inclusion receipt");
        }
        List<byte[]> path = new ArrayList<>();
        for (Object hash : hashes) {
            if (!(hash instanceof byte[] bytes) || bytes.length != 32) {
                throw new IllegalArgumentException("Not an RFC 9162 inclusion receipt");
            }
            path.add(bytes);
        }
        return new LogReceipt(message, issuer, treeSize, leafIndex, List.copyOf(path));
    }

    public Optional<byte[]> root(byte[] leafHash) {
        return MerkleTree.rootFromInclusion(leafHash, leafIndex, treeSize, path);
    }

    public boolean verify(byte[] leafHash, PublicKey key) {
        return root(leafHash).map(root -> message.verify(key, root)).orElse(false);
    }

    public String keyId() {
        return message.keyId();
    }
}
