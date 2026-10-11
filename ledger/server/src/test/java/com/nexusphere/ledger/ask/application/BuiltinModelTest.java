package com.nexusphere.ledger.ask.application;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.Hashes;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinModelTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final PlanContext CONTEXT = new PlanContext(NOW, List.of("invoice-agent", "invoice-agent-2",
            "mail-agent"), List.of("tools/call", "grant/create"), null);

    private final BuiltinModel model = new BuiltinModel();

    private static EvidenceEntry entry(long sequence, String occurredAt, String agent, String principal,
                                       String target, String decision, String outcome) {
        return new EvidenceEntry(UUID.randomUUID(), sequence, Instant.parse(occurredAt), Instant.parse(occurredAt),
                agent, principal, "tools/call", target, decision, null, null, null, null, outcome, null, null,
                new TreeMap<>(), EvidenceEntry.newSalts(Map.of()), null, Hashes.GENESIS, null).sealed();
    }

    @Test
    void aQuestionBecomesASearch() {
        QueryPlan plan = model.plan("What did invoice-agent-2 do for alice last week that was denied?", CONTEXT);

        assertThat(plan.agentId()).isEqualTo("invoice-agent-2");
        assertThat(plan.principalId()).isEqualTo("alice");
        assertThat(plan.decision()).isEqualTo("DENY");
        assertThat(plan.from()).isEqualTo(Instant.parse("2026-10-05T00:00:00Z"));
        assertThat(plan.to()).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
    }

    @Test
    void targetsActionsOutcomesAndPeriodsAreRecognised() {
        QueryPlan plan = model.plan("Which tools/call to files/delete_file failed in the last 3 days?", CONTEXT);

        assertThat(plan.action()).isEqualTo("tools/call");
        assertThat(plan.target()).isEqualTo("files/delete_file");
        assertThat(plan.outcome()).isEqualTo("FAILED");
        assertThat(plan.from()).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"));
        assertThat(model.plan("what happened yesterday", CONTEXT).to()).isEqualTo(Instant.parse("2026-10-14T00:00:00Z"));
        assertThat(model.plan("everything on 2026-10-01", CONTEXT).from())
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(model.plan("show me all actions", CONTEXT)).isEqualTo(QueryPlan.EVERYTHING);
    }

    @Test
    void theAnswerCitesEveryEntryItDescribes() {
        List<EvidenceEntry> evidence = List.of(
                entry(3, "2026-10-13T09:00:00Z", "mail-agent", "alice", "send_email", "ALLOW", "SUCCEEDED"),
                entry(4, "2026-10-13T09:05:00Z", "mail-agent", "alice", "send_email", "ALLOW", "SUCCEEDED"),
                entry(9, "2026-10-14T08:00:00Z", "mail-agent", "alice", "delete_file", "DENY", "DENIED"));

        String answer = model.answer("what did mail-agent do", QueryPlan.EVERYTHING, evidence);
        Citations.Parsed parsed = Citations.parse(answer, Set.of(3L, 4L, 9L));

        assertThat(parsed.dropped()).isZero();
        assertThat(parsed.sentences()).extracting(Citations.Sentence::text).containsExactly(
                "3 verified entries match the question, from 2026-10-13 to 2026-10-14.",
                "On 2026-10-13, mail-agent recorded tools/call on send_email for alice 2 times, allowed and succeeded.",
                "On 2026-10-14, mail-agent recorded tools/call on delete_file for alice once, denied.");
        assertThat(parsed.sentences().stream().flatMap(s -> s.citations().stream()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(3L, 4L, 9L);
        assertThat(model.answer("anything", QueryPlan.EVERYTHING, List.of())).isEqualTo(BuiltinModel.NO_EVIDENCE);
    }
}
