package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceEntryTest {

    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");

    private static EvidenceEntry entry() {
        TreeMap<String, String> attributes = new TreeMap<>();
        attributes.put("customer", "alice@example.com");
        return new EvidenceEntry(UUID.randomUUID(), 1, NOW, NOW, "agent-1", "alice", "tools/call", "crm/lookup",
                "ALLOW", null, null, Hashes.sha256(new byte[]{1}), null, "SUCCEEDED", "conversation-7", attributes,
                Hashes.GENESIS, null).sealed();
    }

    @Test
    void personalValuesAreCommittedWithSaltsAndNeverHashedDirectly() {
        EvidenceEntry entry = entry();
        String content = CanonicalJson.write(entry.canonicalContent());

        assertThat(entry.commitments()).containsOnlyKeys("principalId", "target", "reason", "correlationId",
                "attributes.customer");
        assertThat(content).doesNotContain("alice", "crm/lookup", "conversation-7")
                .contains("\"format\":\"" + EvidenceEntry.FORMAT + "\"");
        assertThat(entry.commitments().get("principalId")).isEqualTo(EvidenceEntry.commitment("principalId",
                entry.salts().get("principalId"), "alice"));
    }

    @Test
    void anErasedEntryKeepsItsHashWithoutAnyPersonalValue() {
        EvidenceEntry entry = entry();
        EvidenceEntry erased = entry.erase();

        assertThat(erased.erased()).isTrue();
        assertThat(erased.principalId()).isNull();
        assertThat(erased.attributes()).isEmpty();
        assertThat(erased.computeHash()).isEqualTo(entry.hash());
        assertThat(ChainVerifier.verify(List.of(erased)).valid()).isTrue();
    }

    @Test
    void aChangedValueOrSaltNoLongerMatchesTheHash() {
        EvidenceEntry entry = entry();
        EvidenceEntry otherPrincipal = new EvidenceEntry(entry.id(), 1, NOW, NOW, "agent-1", "bob", "tools/call",
                "crm/lookup", "ALLOW", null, null, entry.inputHash(), null, "SUCCEEDED", null,
                "conversation-7", entry.attributes(), entry.salts(), null, Hashes.GENESIS, entry.hash());
        TreeMap<String, String> salts = new TreeMap<>(entry.salts());
        salts.put("principalId", "AAAAAAAAAAAAAAAAAAAAAA");
        EvidenceEntry otherSalt = new EvidenceEntry(entry.id(), 1, NOW, NOW, "agent-1", "alice", "tools/call",
                "crm/lookup", "ALLOW", null, null, entry.inputHash(), null, "SUCCEEDED", null,
                "conversation-7", entry.attributes(), salts, null, Hashes.GENESIS, entry.hash());

        assertThat(otherPrincipal.computeHash()).isNotEqualTo(entry.hash());
        assertThat(otherSalt.computeHash()).isNotEqualTo(entry.hash());
    }

    @Test
    void saltsMustCoverEveryPersonalField() {
        EvidenceEntry entry = entry();
        TreeMap<String, String> salts = new TreeMap<>(entry.salts());
        salts.remove("attributes.customer");

        assertThatThrownBy(() -> new EvidenceEntry(entry.id(), 1, NOW, NOW, "agent-1", "alice", "tools/call",
                "crm/lookup", "ALLOW", null, null, null, null, "SUCCEEDED", null, null, entry.attributes(), salts, null,
                Hashes.GENESIS, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceEntry(entry.id(), 1, NOW, NOW, "agent-1", "alice", "tools/call",
                null, "ALLOW", null, null, null, null, "SUCCEEDED", null, null, null, null, entry.commitments(),
                Hashes.GENESIS, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theModelIsHashedOnlyWhenNamed() {
        EvidenceEntry plain = entry();
        String prompt = Hashes.sha256("You pay invoices.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        EvidenceEntry named = new EvidenceEntry(plain.id(), 1, NOW, NOW, "agent-1", "alice", "tools/call",
                "crm/lookup", "ALLOW", null, null, plain.inputHash(), null, "SUCCEEDED",
                AgentModel.of("claude-opus-5-5", "2026-09-01", prompt, null), "conversation-7", plain.attributes(),
                plain.salts(), null, Hashes.GENESIS, null).sealed();

        assertThat(CanonicalJson.write(plain.canonicalContent())).doesNotContain("model");
        assertThat(CanonicalJson.write(named.canonicalContent()))
                .contains("\"model\":{\"name\":\"claude-opus-5-5\",\"promptHash\":\"" + prompt
                        + "\",\"version\":\"2026-09-01\"}");
        assertThat(named.hash()).isNotEqualTo(plain.hash());
        assertThat(named.erase().computeHash()).isEqualTo(named.hash());
        assertThat(AgentModel.of(null, null, null, null)).isNull();
        assertThatThrownBy(() -> AgentModel.of("m", null, "not-a-hash", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
