package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.evidence.domain.model.EvidenceQuery;
import com.nexusphere.ledger.evidence.domain.model.EvidenceSubmission;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class EvidenceService {

    public static final int MAX_PAGE_SIZE = 500;

    private final EvidenceRepository evidence;
    private final Clock clock;

    EvidenceService(EvidenceRepository evidence, Clock clock) {
        this.evidence = evidence;
        this.clock = clock;
    }

    public void check(EvidenceSubmission submission) {
        EvidenceValidator.validate(submission, clock.instant());
    }

    @Transactional
    public EvidenceEntry record(EvidenceSubmission submission) {
        Instant now = clock.instant();
        EvidenceValidator.validate(submission, now);
        LedgerHead head = evidence.lockHead();
        EvidenceEntry entry = new EvidenceEntry(
                UUID.randomUUID(),
                head.sequence() + 1,
                submission.occurredAt() == null ? now : submission.occurredAt(),
                now,
                submission.agentId(),
                submission.principalId(),
                submission.action(),
                submission.target(),
                submission.decision() == null ? null : submission.decision().name(),
                submission.reason(),
                submission.delegationId(),
                submission.inputHash(),
                submission.outputHash(),
                submission.outcome().name(),
                submission.correlationId(),
                new TreeMap<>(submission.attributes() == null ? Map.of() : submission.attributes()),
                head.hash(),
                null).sealed();
        evidence.append(entry);
        return entry;
    }

    @Transactional(readOnly = true)
    public EvidenceEntry get(UUID id) {
        return evidence.findById(id).orElseThrow(() -> LedgerException.notFound("Evidence " + id));
    }

    @Transactional(readOnly = true)
    public List<EvidenceEntry> find(EvidenceQuery query) {
        Paging.check(query.afterSequence(), query.limit(), MAX_PAGE_SIZE);
        return evidence.find(query);
    }

    @Transactional(readOnly = true)
    public LedgerHead head() {
        return evidence.head();
    }
}
