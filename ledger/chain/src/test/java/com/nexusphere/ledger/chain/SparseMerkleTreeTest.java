package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SparseMerkleTreeTest {

    private static final HexFormat HEX = HexFormat.of();

    private static SortedMap<String, byte[]> leaves(int count) {
        SortedMap<String, byte[]> leaves = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            byte[] key = SparseMerkleTree.key(Map.of("type", "agent", "agentId", "agent-" + i));
            leaves.put(HEX.formatHex(key), SparseMerkleTree.valueHash(IndexRoot.value(i + 1, i, i + 5)));
        }
        return leaves;
    }

    private static byte[] reference(SortedMap<String, byte[]> leaves, String prefix) {
        List<Map.Entry<String, byte[]>> under = leaves.entrySet().stream()
                .filter(leaf -> bits(leaf.getKey()).startsWith(prefix)).toList();
        if (under.isEmpty()) {
            return new byte[32];
        }
        if (under.size() == 1) {
            return SparseMerkleTree.leafHash(HEX.parseHex(under.getFirst().getKey()), under.getFirst().getValue());
        }
        byte[] left = reference(leaves, prefix + "0");
        byte[] right = reference(leaves, prefix + "1");
        byte[] node = new byte[65];
        node[0] = 1;
        System.arraycopy(left, 0, node, 1, 32);
        System.arraycopy(right, 0, node, 33, 32);
        return Hashes.sha256Bytes(node);
    }

    private static String bits(String hex) {
        StringBuilder out = new StringBuilder();
        for (byte b : HEX.parseHex(hex)) {
            out.append(String.format("%8s", Integer.toBinaryString(b & 0xff)).replace(' ', '0'));
        }
        return out.toString();
    }

    @Test
    void theRootMatchesADirectComputation() {
        for (int count : List.of(0, 1, 2, 3, 7, 40)) {
            SortedMap<String, byte[]> leaves = leaves(count);
            assertThat(SparseMerkleTree.root(leaves)).as("%d leaves", count).isEqualTo(reference(leaves, ""));
        }
        assertThat(SparseMerkleTree.root(new TreeMap<>())).isEqualTo(SparseMerkleTree.empty());
    }

    @Test
    void everyKeyHasAProofOfItsLeafOrOfItsAbsence() {
        SortedMap<String, byte[]> leaves = leaves(20);
        byte[] root = SparseMerkleTree.root(leaves);
        for (Map.Entry<String, byte[]> leaf : leaves.entrySet()) {
            byte[] key = HEX.parseHex(leaf.getKey());
            SparseMerkleTree.Proof proof = SparseMerkleTree.proof(leaves, key);
            assertThat(proof.otherKey()).isNull();
            assertThat(SparseMerkleTree.rootFrom(key, leaf.getValue(), proof)).isEqualTo(root);
            assertThat(SparseMerkleTree.rootFrom(key, null, proof)).isNotEqualTo(root);
        }
        int others = 0;
        for (int i = 0; i < 30; i++) {
            byte[] missing = SparseMerkleTree.key(Map.of("type", "agent", "agentId", "never-seen-" + i));
            SparseMerkleTree.Proof absent = SparseMerkleTree.proof(leaves, missing);
            others += absent.otherKey() == null ? 0 : 1;
            assertThat(SparseMerkleTree.rootFrom(missing, null, absent)).isEqualTo(root);
            assertThat(SparseMerkleTree.rootFrom(missing, SparseMerkleTree.valueHash(IndexRoot.value(1, 1, 1)),
                    new SparseMerkleTree.Proof(absent.siblings(), null, null))).isNotEqualTo(root);
        }
        assertThat(others).isPositive();
        SortedMap<String, byte[]> one = leaves(1);
        byte[] missing = SparseMerkleTree.key(Map.of("type", "agent", "agentId", "never-seen"));
        SparseMerkleTree.Proof single = SparseMerkleTree.proof(one, missing);
        assertThat(single.siblings()).isEmpty();
        assertThat(SparseMerkleTree.rootFrom(missing, null, single)).isEqualTo(SparseMerkleTree.root(one));
        assertThat(SparseMerkleTree.rootFrom(missing, null, SparseMerkleTree.proof(new TreeMap<>(), missing)))
                .isEqualTo(SparseMerkleTree.empty());
    }

    @Test
    void anotherLeafMustLieOnThePathOfTheKey() {
        SortedMap<String, byte[]> leaves = leaves(20);
        byte[] first = HEX.parseHex(leaves.firstKey());
        byte[] last = HEX.parseHex(leaves.lastKey());
        SparseMerkleTree.Proof proof = SparseMerkleTree.proof(leaves, first);

        assertThatThrownBy(() -> SparseMerkleTree.rootFrom(first, null,
                new SparseMerkleTree.Proof(proof.siblings(), last, leaves.get(leaves.lastKey()))))
                .hasMessageContaining("not on the path");
        assertThatThrownBy(() -> SparseMerkleTree.rootFrom(first, null,
                new SparseMerkleTree.Proof(proof.siblings(), first, leaves.get(leaves.firstKey()))))
                .hasMessageContaining("same key");
    }

    @Test
    void aProofNeedsBothPartsOfTheOtherLeaf() {
        assertThatThrownBy(() -> new SparseMerkleTree.Proof(List.of(), new byte[32], null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void termsAreCheckedAndTheIndexRootIsSigned() {
        assertThat(IndexRoot.term(Map.of("type", "grant", "delegationId", "grant-1")))
                .containsExactly(Map.entry("type", "grant"), Map.entry("delegationId", "grant-1"));
        assertThatThrownBy(() -> IndexRoot.term(Map.of("type", "principal", "principalId", "alice")))
                .hasMessageContaining("type must be one of");
        assertThatThrownBy(() -> IndexRoot.term(Map.of("type", "action", "agentId", "a")))
                .hasMessageContaining("only type and agentId, action");
        assertThat(IndexRoot.terms("a", "tools/call", "ALLOW", null, "g").stream().map(t -> t.get("type")).toList())
                .containsExactly("agent", "action", "grant");
        assertThat(IndexRoot.terms("a", "grant/create", null, null, "g")).hasSize(2);

        IndexRoot root = new IndexRoot(3, Hashes.GENESIS, Hashes.GENESIS, Instant.parse("2026-10-01T08:00:00Z"),
                "06e3fd8fda29bb60");
        assertThat(new String(root.signedBytes())).isEqualTo("{\"createdAt\":\"2026-10-01T08:00:00.000000Z\","
                + "\"format\":\"nexusphere-ledger/index/v1\",\"headHash\":\"" + Hashes.GENESIS + "\",\"keyId\":"
                + "\"06e3fd8fda29bb60\",\"root\":\"" + Hashes.GENESIS + "\",\"sequence\":3}");
    }
}
