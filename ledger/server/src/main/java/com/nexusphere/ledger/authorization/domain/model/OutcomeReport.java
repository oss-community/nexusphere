package com.nexusphere.ledger.authorization.domain.model;

import com.nexusphere.ledger.evidence.domain.model.Outcome;

import java.util.Map;

public record OutcomeReport(Outcome outcome, String outputHash, String reason, Map<String, String> attributes) {
}
