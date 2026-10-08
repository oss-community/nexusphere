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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class EvidenceService {

    public static final int MAX_PAGE_SIZE = 500;
    public static final int MAX_BATCH_SIZE = 500;

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
        return recordAll(List.of(submission)).getFirst();
    }

    @Transactional
    public List<EvidenceEntry> recordAll(List<EvidenceSubmission> submissions) {
        if (submissions == null || submissions.isEmpty() || submissions.size() > MAX_BATCH_SIZE) {
            throw LedgerException.invalid("The batch has invalid fields.",
                    Map.of("items", "must have between 1 and " + MAX_BATCH_SIZE + " entries"));
        }
        Instant now = clock.instant();
        for (int i = 0; i < submissions.size(); i++) {
            try {
                EvidenceValidator.validate(submissions.get(i), now);
            } catch (LedgerException e) {
                if (submissions.size() == 1) {
                    throw e;
                }
                throw new LedgerException(e.status(), e.code(), "Item " + i + ": " + e.getMessage(),
                        Map.of("index", i, "item", e.details()));
            }
        }
        LedgerHead head = evidence.lockHead();
        long sequence = head.sequence();
        String previousHash = head.hash();
        List<EvidenceEntry> entries = new ArrayList<>(submissions.size());
        for (EvidenceSubmission submission : submissions) {
            EvidenceEntry entry = entry(submission, ++sequence, previousHash, now);
            evidence.append(entry);
            entries.add(entry);
            previousHash = entry.hash();
        }
        return entries;
    }

    private static EvidenceEntry entry(EvidenceSubmission submission, long sequence, String previousHash,
                                       Instant now) {
        return new EvidenceEntry(
                UUID.randomUUID(),
                sequence,
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
                previousHash,
                null).sealed();
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
