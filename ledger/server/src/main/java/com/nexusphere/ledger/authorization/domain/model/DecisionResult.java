package com.nexusphere.ledger.authorization.domain.model;

import com.nexusphere.ledger.chain.EvidenceEntry;

import java.util.UUID;

public record DecisionResult(DecisionRecord decision, String reason, EvidenceEntry evidence, UUID approvalId) {

    public DecisionResult(DecisionRecord decision, String reason, EvidenceEntry evidence) {
        this(decision, reason, evidence, null);
    }
}
