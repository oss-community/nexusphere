package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScittTest {

    private final KeyPair key = SigningKeys.generate();
    private final String keyId = SigningKeys.keyIdOf(key.getPublic());
    private final List<EvidenceEntry> entries = ChainVerifierTest.chain(7);
    private final List<byte[]> leaves = entries.stream()
            .map(entry -> MerkleTree.leafHash(HexFormat.of().parseHex(entry.hash()))).toList();
    private final MerkleTree.Subtrees tree = MerkleTree.of(leaves);

    @Test
    void aStatementCommitsToTheEntryAndItsPlaceInTheChain() {
        EvidenceEntry entry = entries.get(3);

        EvidenceStatement statement = EvidenceStatement.parse(
                EvidenceStatement.sign(entry, "https://ledger.example", keyId, key.getPrivate()));

        assertThat(statement.verify(key.getPublic())).isTrue();
        assertThat(statement.verify(SigningKeys.generate().getPublic())).isFalse();
        assertThat(statement.keyId()).isEqualTo(keyId);
        assertThat(statement.issuer()).isEqualTo("https://ledger.example");
        assertThat(statement.subject()).isEqualTo("urn:uuid:" + entry.id());
        assertThat(statement.contentHash()).isEqualTo(entry.contentHash());
        assertThat(statement.entryHash()).isEqualTo(entry.hash());
        assertThat(statement.describes(entry.link())).isTrue();
        assertThat(statement.describes(entries.get(2).link())).isFalse();
    }

    @Test
    void aReceiptProvesTheStatementInTheSignedTree() {
        EvidenceEntry entry = entries.get(4);
        EvidenceStatement statement = EvidenceStatement.parse(
                EvidenceStatement.sign(entry, "https://ledger.example", keyId, key.getPrivate()));
        byte[] root = MerkleTree.root(tree, 7);

        LogReceipt receipt = LogReceipt.parse(LogReceipt.sign("ledger.example", keyId, 7, 4,
                MerkleTree.inclusionProof(tree, 4, 7), root, key.getPrivate()));

        assertThat(receipt.treeSize()).isEqualTo(7);
        assertThat(receipt.leafIndex()).isEqualTo(4);
        assertThat(receipt.issuer()).isEqualTo("ledger.example");
        assertThat(receipt.root(statement.leafHash())).hasValueSatisfying(r -> assertThat(r).isEqualTo(root));
        assertThat(receipt.verify(statement.leafHash(), key.getPublic())).isTrue();
        assertThat(receipt.verify(leaves.get(3), key.getPublic())).isFalse();
        assertThat(receipt.verify(statement.leafHash(), SigningKeys.generate().getPublic())).isFalse();
    }

    @Test
    void aChangedStatementNoLongerVerifies() {
        byte[] signed = EvidenceStatement.sign(entries.get(0), "https://ledger.example", keyId, key.getPrivate());
        byte[] changed = signed.clone();
        changed[changed.length - 70] ^= 1;

        assertThat(EvidenceStatement.parse(signed).verify(key.getPublic())).isTrue();
        assertThat(EvidenceStatement.parse(changed).verify(key.getPublic())).isFalse();
    }

    @Test
    void otherCoseMessagesAreNotTakenForStatementsOrReceipts() {
        byte[] receipt = LogReceipt.sign("ledger.example", keyId, 1, 0, List.of(), leaves.get(0), key.getPrivate());
        byte[] statement = EvidenceStatement.sign(entries.get(0), "https://ledger.example", keyId,
                key.getPrivate());

        assertThatThrownBy(() -> EvidenceStatement.parse(receipt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogReceipt.parse(statement)).isInstanceOf(IllegalArgumentException.class);
    }
}
