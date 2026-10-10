package com.nexusphere.ledger.chain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

public final class SparseMerkleTree {

    public static final int DEPTH = 256;
    private static final byte LEAF = 0;
    private static final byte NODE = 1;
    private static final byte[] EMPTY = new byte[32];

    private SparseMerkleTree() {
    }

    public record Proof(List<byte[]> siblings, byte[] otherKey, byte[] otherValueHash) {

        public Proof {
            siblings = List.copyOf(siblings);
            if (siblings.size() > DEPTH) {
                throw new IllegalArgumentException("a proof has at most " + DEPTH + " siblings");
            }
            if ((otherKey == null) != (otherValueHash == null)) {
                throw new IllegalArgumentException("another leaf needs both its key and its value hash");
            }
        }
    }

    private record Leaf(byte[] key, byte[] valueHash) {
    }

    public static byte[] empty() {
        return EMPTY.clone();
    }

    public static byte[] key(Map<String, ?> term) {
        return Hashes.sha256Bytes(CanonicalJson.bytes(term));
    }

    public static byte[] valueHash(Map<String, ?> value) {
        return Hashes.sha256Bytes(CanonicalJson.bytes(value));
    }

    public static byte[] leafHash(byte[] key, byte[] valueHash) {
        return digest(LEAF, key, valueHash);
    }

    public static byte[] root(SortedMap<String, byte[]> values) {
        List<Leaf> leaves = leaves(values);
        return subtree(leaves, 0, leaves.size(), 0);
    }

    public static Proof proof(SortedMap<String, byte[]> values, byte[] key) {
        List<Leaf> leaves = leaves(values);
        List<byte[]> siblings = new ArrayList<>();
        int lo = 0;
        int hi = leaves.size();
        int depth = 0;
        while (hi - lo > 1) {
            int mid = split(leaves, lo, hi, depth);
            if (bit(key, depth) == 0) {
                siblings.add(subtree(leaves, mid, hi, depth + 1));
                hi = mid;
            } else {
                siblings.add(subtree(leaves, lo, mid, depth + 1));
                lo = mid;
            }
            depth++;
        }
        if (hi - lo == 1 && !Arrays.equals(leaves.get(lo).key(), key)) {
            return new Proof(siblings, leaves.get(lo).key(), leaves.get(lo).valueHash());
        }
        return new Proof(siblings, null, null);
    }

    public static byte[] rootFrom(byte[] key, byte[] valueHash, Proof proof) {
        int depth = proof.siblings().size();
        byte[] hash;
        if (valueHash != null) {
            if (proof.otherKey() != null) {
                throw new IllegalArgumentException("a proof of a leaf cannot name another leaf");
            }
            hash = leafHash(key, valueHash);
        } else if (proof.otherKey() != null) {
            if (Arrays.equals(proof.otherKey(), key)) {
                throw new IllegalArgumentException("the other leaf of a proof of absence has the same key");
            }
            for (int d = 0; d < depth; d++) {
                if (bit(proof.otherKey(), d) != bit(key, d)) {
                    throw new IllegalArgumentException("the other leaf is not on the path of the key");
                }
            }
            hash = leafHash(proof.otherKey(), proof.otherValueHash());
        } else {
            hash = EMPTY;
        }
        for (int d = depth - 1; d >= 0; d--) {
            byte[] sibling = proof.siblings().get(d);
            hash = bit(key, d) == 0 ? digest(NODE, hash, sibling) : digest(NODE, sibling, hash);
        }
        return hash;
    }

    private static List<Leaf> leaves(SortedMap<String, byte[]> values) {
        List<Leaf> leaves = new ArrayList<>(values.size());
        HexFormat hex = HexFormat.of();
        values.forEach((key, valueHash) -> {
            if (!Hashes.isSha256(key)) {
                throw new IllegalArgumentException("a key must be a lowercase hex SHA-256");
            }
            leaves.add(new Leaf(hex.parseHex(key), valueHash));
        });
        return leaves;
    }

    private static byte[] subtree(List<Leaf> leaves, int lo, int hi, int depth) {
        if (lo == hi) {
            return EMPTY;
        }
        if (hi - lo == 1) {
            return leafHash(leaves.get(lo).key(), leaves.get(lo).valueHash());
        }
        int mid = split(leaves, lo, hi, depth);
        return digest(NODE, subtree(leaves, lo, mid, depth + 1), subtree(leaves, mid, hi, depth + 1));
    }

    private static int split(List<Leaf> leaves, int lo, int hi, int depth) {
        int low = lo;
        int high = hi;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (bit(leaves.get(mid).key(), depth) == 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    static int bit(byte[] bytes, int index) {
        return (bytes[index / 8] >> (7 - index % 8)) & 1;
    }

    private static byte[] digest(byte prefix, byte[] first, byte[] second) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix);
            digest.update(first);
            digest.update(second);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
