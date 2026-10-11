package com.nexusphere.ledger.evidence.domain.model;

import java.time.Instant;

public record EvidenceSearch(String agentId, String principalId, String action, String decision, String outcome,
                             Instant from, Instant to, int limit) {
}
