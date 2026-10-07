package com.nexusphere.ledger.authorization.domain.repository;

import com.nexusphere.ledger.authorization.domain.model.DecisionRecord;
import com.nexusphere.ledger.evidence.domain.model.Outcome;

import java.util.Optional;
import java.util.UUID;

public interface DecisionRepository {

    void insert(DecisionRecord decision);

    Optional<DecisionRecord> find(UUID id);

    Optional<DecisionRecord> lock(UUID id);

    void recordOutcome(UUID id, Outcome outcome, UUID outcomeEvidenceId);
}
