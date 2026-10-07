package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.web.LedgerException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class EvidenceValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");

    private static EvidenceSubmission submission(Decision decision, Outcome outcome, String inputHash,
                                                 Instant occurredAt, Map<String, String> attributes) {
        return new EvidenceSubmission(occurredAt, "agent-1", "user-1", "tools/call", "search", decision, null, null,
                inputHash, null, outcome, null, attributes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(EvidenceSubmission submission) {
        LedgerException e = catchThrowableOfType(LedgerException.class,
                () -> EvidenceValidator.validate(submission, NOW));
        assertThat(e).isNotNull();
        return (Map<String, Object>) e.details().get("fields");
    }

    @Test
    void acceptsACompleteSubmission() {
        assertThatCode(() -> EvidenceValidator.validate(
                submission(Decision.ALLOW, Outcome.SUCCEEDED, Hashes.sha256(new byte[]{1}), NOW, Map.of("k", "v")),
                NOW)).doesNotThrowAnyException();
    }

    @Test
    void requiresTheAccountableFields() {
        EvidenceSubmission empty = new EvidenceSubmission(null, null, " ", null, null, null, null, null, null, null,
                null, null, null);

        assertThat(fieldsOf(empty)).containsKeys("agentId", "principalId", "action", "outcome");
    }

    @Test
    void rejectsADeniedDecisionWithASuccessfulOutcome() {
        assertThat(fieldsOf(submission(Decision.DENY, Outcome.SUCCEEDED, null, null, null))).containsKey("outcome");
        assertThat(fieldsOf(submission(Decision.ALLOW, Outcome.DENIED, null, null, null))).containsKey("decision");
    }

    @Test
    void rejectsMalformedHashesAndFutureTimes() {
        Map<String, Object> fields = fieldsOf(submission(Decision.ALLOW, Outcome.SUCCEEDED, "ABC",
                NOW.plus(Duration.ofHours(1)), null));

        assertThat(fields).containsKeys("inputHash", "occurredAt");
    }

    @Test
    void limitsAttributes() {
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i <= EvidenceValidator.MAX_ATTRIBUTES; i++) {
            tooMany.put("k" + i, "v");
        }

        assertThat(fieldsOf(submission(Decision.ALLOW, Outcome.SUCCEEDED, null, null, tooMany)))
                .containsKey("attributes");
        assertThat(fieldsOf(submission(Decision.ALLOW, Outcome.SUCCEEDED, null, null, Map.of("bad name", "v"))))
                .containsKey("attributes");
    }
}
