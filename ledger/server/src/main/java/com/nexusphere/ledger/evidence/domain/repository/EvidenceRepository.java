package com.nexusphere.ledger.evidence.domain.repository;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.evidence.domain.model.Activity;
import com.nexusphere.ledger.evidence.domain.model.EvidenceQuery;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;
import com.nexusphere.ledger.evidence.domain.model.Selection;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EvidenceRepository {

    LedgerHead lockHead();

    LedgerHead head();

    void append(EvidenceEntry entry);

    Optional<EvidenceEntry> findById(UUID id);

    Optional<EvidenceEntry> findBySequence(long sequence);

    Optional<EvidenceEntry> latestByAction(String action);

    List<EvidenceEntry> find(EvidenceQuery query);

    List<EvidenceEntry> recent(String principalId, long beforeSequence, int limit);

    List<EvidenceEntry> range(long afterSequence, int limit);

    List<String> hashes(long afterSequence, int limit);

    Activity activity(String principalId, Instant since);

    Selection select(String agentId, String principalId, long fromSequence, long toSequence);
}
