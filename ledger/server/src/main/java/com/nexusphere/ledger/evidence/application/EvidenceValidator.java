package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.web.LedgerException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class EvidenceValidator {

    static final int MAX_ATTRIBUTES = 32;
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Map<String, Object> errors = new LinkedHashMap<>();

    private EvidenceValidator() {
    }

    static void validate(EvidenceSubmission s, Instant now) {
        EvidenceValidator v = new EvidenceValidator();
        v.text("agentId", s.agentId(), true, 200);
        v.text("principalId", s.principalId(), true, 200);
        v.text("action", s.action(), true, 120);
        v.text("target", s.target(), false, 300);
        v.text("reason", s.reason(), false, 500);
        v.text("delegationId", s.delegationId(), false, 200);
        v.text("correlationId", s.correlationId(), false, 128);
        v.hash("inputHash", s.inputHash());
        v.hash("outputHash", s.outputHash());
        if (s.outcome() == null) {
            v.errors.put("outcome", "must be one of SUCCEEDED, FAILED, DENIED, PENDING");
        }
        if (s.decision() == Decision.DENY && s.outcome() != null && s.outcome() != Outcome.DENIED) {
            v.errors.put("outcome", "must be DENIED when the decision is DENY");
        }
        if (s.outcome() == Outcome.DENIED && s.decision() == Decision.ALLOW) {
            v.errors.put("decision", "must not be ALLOW when the outcome is DENIED");
        }
        if (s.outcome() == Outcome.PENDING && s.decision() != Decision.ALLOW) {
            v.errors.put("decision", "must be ALLOW when the outcome is PENDING");
        }
        if (s.occurredAt() != null && s.occurredAt().isAfter(now.plus(MAX_CLOCK_SKEW))) {
            v.errors.put("occurredAt", "must not be in the future");
        }
        v.attributes(s.attributes());
        if (!v.errors.isEmpty()) {
            throw LedgerException.invalid("The evidence has invalid fields.", v.errors);
        }
    }

    private void text(String field, String value, boolean required, int maxLength) {
        if (value == null) {
            if (required) {
                errors.put(field, "is required");
            }
            return;
        }
        if (value.isBlank()) {
            errors.put(field, "must not be blank");
        } else if (value.length() > maxLength) {
            errors.put(field, "must be at most " + maxLength + " characters");
        } else if (value.chars().anyMatch(Character::isISOControl)) {
            errors.put(field, "must not contain control characters");
        }
    }

    private void hash(String field, String value) {
        if (value != null && !Hashes.isSha256(value)) {
            errors.put(field, "must be a lowercase hex SHA-256 digest");
        }
    }

    private void attributes(Map<String, String> attributes) {
        if (attributes == null) {
            return;
        }
        if (attributes.size() > MAX_ATTRIBUTES) {
            errors.put("attributes", "must have at most " + MAX_ATTRIBUTES + " entries");
            return;
        }
        attributes.forEach((name, value) -> {
            if (name == null || !ATTRIBUTE_NAME.matcher(name).matches()) {
                errors.putIfAbsent("attributes", "names must match " + ATTRIBUTE_NAME.pattern());
            } else if (value == null || value.length() > 1024) {
                errors.putIfAbsent("attributes." + name, "must be a string of at most 1024 characters");
            }
        });
    }
}
