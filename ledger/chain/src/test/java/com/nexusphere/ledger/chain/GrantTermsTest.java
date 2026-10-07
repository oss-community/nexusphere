package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GrantTermsTest {

    private static GrantTerms terms(List<String> actions, List<String> targets) {
        return new GrantTerms(UUID.fromString("00000000-0000-0000-0000-000000000001"), "alice", "invoice-agent",
                actions, targets, null, Instant.parse("2026-12-01T00:00:00Z"), 10L,
                Instant.parse("2026-10-01T00:00:00.123456789Z"));
    }

    @Test
    void coversExactAndPrefixPatterns() {
        GrantTerms terms = terms(List.of("tools/call"), List.of("send_email", "read_*"));

        assertThat(terms.covers("tools/call", "send_email")).isTrue();
        assertThat(terms.covers("tools/call", "read_file")).isTrue();
        assertThat(terms.covers("tools/call", "delete_file")).isFalse();
        assertThat(terms.covers("resources/read", "send_email")).isFalse();
        assertThat(terms.covers("tools/call", null)).isFalse();
    }

    @Test
    void aWildcardCoversEverything() {
        GrantTerms terms = terms(List.of("*"), List.of("*"));

        assertThat(terms.covers("anything", "at-all")).isTrue();
        assertThat(terms.covers("anything", null)).isTrue();
    }

    @Test
    void theHashCoversEveryTermAndIsStable() {
        GrantTerms terms = terms(List.of("tools/call"), List.of("send_email"));
        GrantTerms wider = terms(List.of("tools/call"), List.of("send_email", "delete_*"));

        assertThat(terms.hash()).isEqualTo(terms(List.of("tools/call"), List.of("send_email")).hash());
        assertThat(terms.hash()).isNotEqualTo(wider.hash());
        assertThat(CanonicalJson.write(terms.canonicalContent()))
                .contains("\"createdAt\":\"2026-10-01T00:00:00.123456Z\"")
                .contains("\"format\":\"nexusphere-ledger/grant/v1\"");
    }
}
