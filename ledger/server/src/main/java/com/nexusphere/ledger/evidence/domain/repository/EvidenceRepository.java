package com.nexusphere.ledger.evidence.domain.repository;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.evidence.domain.model.EvidenceQuery;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceRepository {

    LedgerHead lockHead();

    LedgerHead head();

    void append(EvidenceEntry entry);

    Optional<EvidenceEntry> findById(UUID id);

    Optional<EvidenceEntry> findBySequence(long sequence);

    List<EvidenceEntry> find(EvidenceQuery query);

    List<EvidenceEntry> range(long afterSequence, int limit);
}
