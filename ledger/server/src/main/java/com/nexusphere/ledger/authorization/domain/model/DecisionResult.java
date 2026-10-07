package com.nexusphere.ledger.authorization.domain.model;

import com.nexusphere.ledger.chain.EvidenceEntry;

public record DecisionResult(DecisionRecord decision, String reason, EvidenceEntry evidence) {
}
