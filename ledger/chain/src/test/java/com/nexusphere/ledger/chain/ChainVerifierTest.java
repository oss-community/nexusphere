package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChainVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00.123456789Z");

    static List<EvidenceEntry> chain(int length) {
        List<EvidenceEntry> entries = new ArrayList<>();
        String previous = Hashes.GENESIS;
        for (int i = 1; i <= length; i++) {
            TreeMap<String, String> attributes = new TreeMap<>();
            attributes.put("tool", "search");
            EvidenceEntry entry = new EvidenceEntry(UUID.randomUUID(), i, NOW.plusSeconds(i), NOW.plusSeconds(i),
                    "agent-1", "user-1", "tools/call", "search", "ALLOW", null, null, Hashes.sha256(new byte[]{1}),
                    null, "SUCCEEDED", "corr-" + i, attributes, previous, null).sealed();
            entries.add(entry);
            previous = entry.hash();
        }
        return entries;
    }

    static EvidenceEntry withAgent(EvidenceEntry e, String agentId) {
        return new EvidenceEntry(e.id(), e.sequence(), e.occurredAt(), e.recordedAt(), agentId, e.principalId(),
                e.action(), e.target(), e.decision(), e.reason(), e.delegationId(), e.inputHash(), e.outputHash(),
                e.outcome(), e.correlationId(), e.attributes(), e.previousHash(), e.hash());
    }

    @Test
    void sealedEntriesFormAValidChain() {
        List<EvidenceEntry> entries = chain(5);

        ChainVerification result = ChainVerifier.verify(entries);

        assertThat(result.valid()).isTrue();
        assertThat(result.checkedEntries()).isEqualTo(5);
        assertThat(result.lastSequence()).isEqualTo(5);
        assertThat(result.lastHash()).isEqualTo(entries.get(4).hash());
    }

    @Test
    void anEmptyChainIsValid() {
        ChainVerification result = ChainVerifier.verify(List.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.lastSequence()).isZero();
        assertThat(result.lastHash()).isEqualTo(Hashes.GENESIS);
    }

    @Test
    void changedContentIsDetectedAtTheChangedEntry() {
        List<EvidenceEntry> entries = new ArrayList<>(chain(5));
        entries.set(2, withAgent(entries.get(2), "agent-2"));

        ChainVerification result = ChainVerifier.verify(entries);

        assertThat(result.valid()).isFalse();
        assertThat(result.failedSequence()).isEqualTo(3);
        assertThat(result.checkedEntries()).isEqualTo(2);
        assertThat(result.failure()).contains("hash");
    }

    @Test
    void resealedContentBreaksTheLinkOfTheNextEntry() {
        List<EvidenceEntry> entries = new ArrayList<>(chain(5));
        entries.set(2, withAgent(entries.get(2), "agent-2").sealed());

        ChainVerification result = ChainVerifier.verify(entries);

        assertThat(result.valid()).isFalse();
        assertThat(result.failedSequence()).isEqualTo(4);
        assertThat(result.failure()).contains("previous hash");
    }

    @Test
    void aRemovedEntryIsDetectedAsASequenceGap() {
        List<EvidenceEntry> entries = new ArrayList<>(chain(5));
        entries.remove(1);

        ChainVerification result = ChainVerifier.verify(entries);

        assertThat(result.valid()).isFalse();
        assertThat(result.failedSequence()).isEqualTo(3);
        assertThat(result.failure()).contains("expected sequence 2");
    }

    @Test
    void hashIgnoresSubMicrosecondPrecision() {
        EvidenceEntry entry = chain(1).getFirst();

        assertThat(entry.recordedAt().getNano() % 1000).isZero();
        assertThat(entry.computeHash()).isEqualTo(entry.hash());
    }

    @Test
    void redactedLinksKeepTheChainVerifiable() {
        List<EvidenceEntry> entries = chain(5);
        ChainVerifier verifier = new ChainVerifier();

        verifier.accept(entries.get(0).link());
        verifier.accept(entries.get(1));
        verifier.accept(entries.get(2).link());
        verifier.accept(entries.get(3).link());
        verifier.accept(entries.get(4));

        assertThat(verifier.result().valid()).isTrue();
        assertThat(verifier.result().lastHash()).isEqualTo(entries.get(4).hash());
    }

    @Test
    void aForgedRedactedLinkIsDetected() {
        List<EvidenceEntry> entries = chain(3);
        EvidenceLink genuine = entries.get(1).link();
        EvidenceLink forged = new EvidenceLink(genuine.sequence(), genuine.previousHash(),
                Hashes.sha256(new byte[]{9}), genuine.hash());
        ChainVerifier verifier = new ChainVerifier();

        verifier.accept(entries.get(0));
        verifier.accept(forged);

        assertThat(verifier.result().valid()).isFalse();
        assertThat(verifier.result().failedSequence()).isEqualTo(2);
    }

    @Test
    void verificationCanStartAfterAnAnchor() {
        List<EvidenceEntry> entries = chain(4);
        ChainVerifier verifier = new ChainVerifier(3, entries.get(1).hash());

        verifier.accept(entries.get(2));
        verifier.accept(entries.get(3));

        assertThat(verifier.result().valid()).isTrue();
        assertThat(verifier.result().checkedEntries()).isEqualTo(2);
    }
}
