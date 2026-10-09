package com.nexusphere.ledger.chain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MerkleTree {

    public interface Subtrees {

        byte[] perfect(int level, long index);
    }

    public static final class Builder {

        private final List<byte[]> stack = new ArrayList<>();
        private long size;

        public void add(byte[] leafHash) {
            byte[] hash = leafHash;
            long index = size;
            while ((index & 1) == 1) {
                hash = nodeHash(stack.removeLast(), hash);
                index >>= 1;
            }
            stack.add(hash);
            size++;
        }

        public long size() {
            return size;
        }

        public byte[] root() {
            if (size == 0) {
                return emptyRoot();
            }
            byte[] root = stack.getLast();
            for (int i = stack.size() - 2; i >= 0; i--) {
                root = nodeHash(stack.get(i), root);
            }
            return root;
        }
    }

    private static final byte LEAF = 0x00;
    private static final byte NODE = 0x01;

    private MerkleTree() {
    }

    public static byte[] leafHash(byte[] data) {
        return digest(new byte[]{LEAF}, data);
    }

    public static byte[] nodeHash(byte[] left, byte[] right) {
        return digest(new byte[]{NODE}, left, right);
    }

    public static byte[] emptyRoot() {
        return digest();
    }

    public static byte[] root(Subtrees subtrees, long size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        return size == 0 ? emptyRoot() : hash(subtrees, 0, size);
    }

    public static List<byte[]> inclusionProof(Subtrees subtrees, long index, long size) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("index must be below the tree size");
        }
        List<byte[]> proof = new ArrayList<>();
        path(subtrees, index, 0, size, proof);
        return proof;
    }

    public static List<byte[]> consistencyProof(Subtrees subtrees, long first, long second) {
        if (first < 0 || first > second) {
            throw new IllegalArgumentException("the first size must be between 0 and the second size");
        }
        List<byte[]> proof = new ArrayList<>();
        if (first > 0 && first < second) {
            subproof(subtrees, first, 0, second, true, proof);
        }
        return proof;
    }

    public static boolean verifyInclusion(byte[] leafHash, long index, long size, List<byte[]> proof, byte[] root) {
        if (index < 0 || index >= size) {
            return false;
        }
        long fn = index;
        long sn = size - 1;
        byte[] r = leafHash;
        for (byte[] p : proof) {
            if (sn == 0) {
                return false;
            }
            if ((fn & 1) == 1 || fn == sn) {
                r = nodeHash(p, r);
                if ((fn & 1) == 0) {
                    while ((fn & 1) == 0 && fn != 0) {
                        fn >>= 1;
                        sn >>= 1;
                    }
                }
            } else {
                r = nodeHash(r, p);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && Arrays.equals(r, root);
    }

    public static boolean verifyConsistency(long first, long second, List<byte[]> proof, byte[] firstRoot,
                                            byte[] secondRoot) {
        if (first < 0 || first > second) {
            return false;
        }
        if (first == second) {
            return proof.isEmpty() && Arrays.equals(firstRoot, secondRoot);
        }
        if (first == 0) {
            return proof.isEmpty();
        }
        List<byte[]> path = new ArrayList<>(proof);
        if (Long.bitCount(first) == 1) {
            path.addFirst(firstRoot);
        }
        if (path.isEmpty()) {
            return false;
        }
        long fn = first - 1;
        long sn = second - 1;
        while ((fn & 1) == 1) {
            fn >>= 1;
            sn >>= 1;
        }
        byte[] fr = path.getFirst();
        byte[] sr = path.getFirst();
        for (byte[] c : path.subList(1, path.size())) {
            if (sn == 0) {
                return false;
            }
            if ((fn & 1) == 1 || fn == sn) {
                fr = nodeHash(c, fr);
                sr = nodeHash(c, sr);
                while ((fn & 1) == 0 && fn != 0) {
                    fn >>= 1;
                    sn >>= 1;
                }
            } else {
                sr = nodeHash(sr, c);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && Arrays.equals(fr, firstRoot) && Arrays.equals(sr, secondRoot);
    }

    public static Subtrees of(List<byte[]> leafHashes) {
        List<List<byte[]>> levels = new ArrayList<>();
        levels.add(List.copyOf(leafHashes));
        while (levels.getLast().size() > 1) {
            List<byte[]> below = levels.getLast();
            List<byte[]> above = new ArrayList<>(below.size() / 2);
            for (int i = 0; i + 1 < below.size(); i += 2) {
                above.add(nodeHash(below.get(i), below.get(i + 1)));
            }
            levels.add(above);
        }
        return (level, index) -> levels.get(level).get(Math.toIntExact(index));
    }

    static long split(long size) {
        return Long.highestOneBit(size - 1);
    }

    private static byte[] hash(Subtrees subtrees, long start, long size) {
        if (Long.bitCount(size) == 1 && start % size == 0) {
            int level = Long.numberOfTrailingZeros(size);
            return subtrees.perfect(level, start >> level);
        }
        long k = split(size);
        return nodeHash(hash(subtrees, start, k), hash(subtrees, start + k, size - k));
    }

    private static void path(Subtrees subtrees, long index, long start, long size, List<byte[]> proof) {
        if (size == 1) {
            return;
        }
        long k = split(size);
        if (index < k) {
            path(subtrees, index, start, k, proof);
            proof.add(hash(subtrees, start + k, size - k));
        } else {
            path(subtrees, index - k, start + k, size - k, proof);
            proof.add(hash(subtrees, start, k));
        }
    }

    private static void subproof(Subtrees subtrees, long first, long start, long size, boolean complete,
                                 List<byte[]> proof) {
        if (first == size) {
            if (!complete) {
                proof.add(hash(subtrees, start, size));
            }
            return;
        }
        long k = split(size);
        if (first <= k) {
            subproof(subtrees, first, start, k, complete, proof);
            proof.add(hash(subtrees, start + k, size - k));
        } else {
            subproof(subtrees, first - k, start + k, size - k, false, proof);
            proof.add(hash(subtrees, start, k));
        }
    }

    private static byte[] digest(byte[]... parts) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                sha256.update(part);
            }
            return sha256.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
