package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MerkleTreeTest {

    private static final int MAX = 40;

    private static List<byte[]> leaves(int count) {
        List<byte[]> leaves = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            leaves.add(MerkleTree.leafHash(("leaf " + i).getBytes(StandardCharsets.UTF_8)));
        }
        return leaves;
    }

    private static byte[] reference(List<byte[]> leaves) {
        if (leaves.isEmpty()) {
            return MerkleTree.emptyRoot();
        }
        if (leaves.size() == 1) {
            return leaves.getFirst();
        }
        int k = Integer.highestOneBit(leaves.size() - 1);
        return MerkleTree.nodeHash(reference(leaves.subList(0, k)), reference(leaves.subList(k, leaves.size())));
    }

    @Test
    void theRootMatchesTheDefinitionForEverySize() {
        List<byte[]> all = leaves(MAX);
        MerkleTree.Subtrees subtrees = MerkleTree.of(all);
        for (int size = 0; size <= MAX; size++) {
            assertThat(MerkleTree.root(subtrees, size)).isEqualTo(reference(all.subList(0, size)));
        }
    }

    @Test
    void theBuilderAgreesWithTheDefinitionAfterEveryLeaf() {
        List<byte[]> all = leaves(MAX);
        MerkleTree.Builder builder = new MerkleTree.Builder();
        assertThat(builder.root()).isEqualTo(MerkleTree.emptyRoot());
        for (int size = 1; size <= MAX; size++) {
            builder.add(all.get(size - 1));
            assertThat(builder.size()).isEqualTo(size);
            assertThat(builder.root()).isEqualTo(reference(all.subList(0, size)));
        }
    }

    @Test
    void everyLeafIsProvenInEveryTreeThatHoldsIt() {
        List<byte[]> all = leaves(MAX);
        MerkleTree.Subtrees subtrees = MerkleTree.of(all);
        for (int size = 1; size <= MAX; size++) {
            byte[] root = MerkleTree.root(subtrees, size);
            for (int index = 0; index < size; index++) {
                List<byte[]> proof = MerkleTree.inclusionProof(subtrees, index, size);
                assertThat(MerkleTree.verifyInclusion(all.get(index), index, size, proof, root)).isTrue();
                assertThat(MerkleTree.verifyInclusion(all.get((index + 1) % MAX), index, size, proof, root))
                        .isEqualTo(size == 1 && MAX == 1);
            }
        }
    }

    @Test
    void everyOlderTreeIsProvenConsistentWithEveryNewerTree() {
        List<byte[]> all = leaves(MAX);
        MerkleTree.Subtrees subtrees = MerkleTree.of(all);
        for (int second = 1; second <= MAX; second++) {
            byte[] secondRoot = MerkleTree.root(subtrees, second);
            for (int first = 1; first <= second; first++) {
                byte[] firstRoot = MerkleTree.root(subtrees, first);
                List<byte[]> proof = MerkleTree.consistencyProof(subtrees, first, second);
                assertThat(MerkleTree.verifyConsistency(first, second, proof, firstRoot, secondRoot)).isTrue();
                if (first < second) {
                    assertThat(MerkleTree.verifyConsistency(first, second, proof, secondRoot, secondRoot)).isFalse();
                }
            }
        }
    }

    @Test
    void aRewrittenHistoryIsNotConsistent() {
        List<byte[]> original = leaves(10);
        List<byte[]> forked = new ArrayList<>(original);
        forked.set(3, MerkleTree.leafHash("changed".getBytes(StandardCharsets.UTF_8)));
        byte[] oldRoot = MerkleTree.root(MerkleTree.of(original), 7);
        MerkleTree.Subtrees fork = MerkleTree.of(forked);

        List<byte[]> proof = MerkleTree.consistencyProof(fork, 7, 10);

        assertThat(MerkleTree.verifyConsistency(7, 10, proof, oldRoot, MerkleTree.root(fork, 10))).isFalse();
    }
}
