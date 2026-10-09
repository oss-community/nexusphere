package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.domain.model.EvidencePackage;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;
import com.nexusphere.ledger.evidence.domain.model.PackageRequest;
import com.nexusphere.ledger.evidence.domain.model.Selection;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.transparency.application.TransparencyLog;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository.StoredCheckpoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PackageService {

    public static final long MAX_LINKS = 200_000;
    private static final int PAGE = 1000;

    private final EvidenceRepository evidence;
    private final CheckpointRepository checkpoints;
    private final CheckpointService checkpointService;
    private final TransparencyLog log;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    PackageService(EvidenceRepository evidence, CheckpointRepository checkpoints, CheckpointService checkpointService,
                   TransparencyLog log, PlatformTransactionManager transactions, Clock clock) {
        this.evidence = evidence;
        this.checkpoints = checkpoints;
        this.checkpointService = checkpointService;
        this.log = log;
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshot.setReadOnly(true);
        this.clock = clock;
    }

    public EvidencePackage export(PackageRequest request) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (request.fromSequence() != null && request.fromSequence() < 1) {
            errors.put("fromSequence", "must be at least 1");
        }
        if (request.toSequence() != null && request.toSequence() < 1) {
            errors.put("toSequence", "must be at least 1");
        }
        if (!errors.isEmpty()) {
            throw LedgerException.invalid("The package request has invalid fields.", errors);
        }
        LedgerHead head = evidence.head();
        if (head.sequence() == 0) {
            throw LedgerException.conflict("LEDGER_EMPTY", "The ledger has no evidence yet.");
        }
        long from = request.fromSequence() == null ? 1 : request.fromSequence();
        long to = Math.min(request.toSequence() == null ? head.sequence() : request.toSequence(), head.sequence());
        Selection selection = evidence.select(request.agentId(), request.principalId(), from, to);
        if (selection.count() == 0) {
            throw LedgerException.conflict("NOTHING_SELECTED", "No evidence matches the package request.");
        }
        SignedCheckpoint end = checkpoints.firstAtOrAfter(selection.lastSequence())
                .orElseGet(() -> checkpointService.checkpointHead().orElseThrow());
        SignedCheckpoint anchor = checkpoints.lastBefore(selection.firstSequence()).orElse(null);
        long after = anchor == null ? 0 : anchor.checkpoint().sequence();
        long last = end.checkpoint().sequence();
        if (last - after > MAX_LINKS) {
            throw LedgerException.conflict("PACKAGE_TOO_LARGE", "The package would hold " + (last - after)
                    + " links; narrow the request or create checkpoints more often.");
        }
        StoredCheckpoint logCheckpoint = log.checkpoint(last, clock.instant());
        List<EvidencePackage.Item> items = snapshot.execute(status -> collect(request, from, to, after, last));
        String expected = anchor == null ? Hashes.GENESIS : anchor.checkpoint().headHash();
        if (items == null || items.isEmpty() || !Objects.equals(items.getFirst().link().previousHash(), expected)) {
            throw new IllegalStateException("The evidence package does not start at its anchor");
        }
        List<Long> disclosed = items.stream().filter(item -> item.entry() != null)
                .map(item -> item.link().sequence()).toList();
        EvidencePackage.Log proofs = snapshot.execute(status -> new EvidencePackage.Log(
                log.cosignedNote(logCheckpoint), log.inclusionProofs(disclosed, last)));
        return new EvidencePackage(clock.instant(), request.agentId(), request.principalId(), from, to, anchor, end,
                items, proofs);
    }

    private List<EvidencePackage.Item> collect(PackageRequest request, long from, long to, long after, long last) {
        List<EvidencePackage.Item> items = new ArrayList<>();
        long cursor = after;
        while (cursor < last) {
            List<EvidenceEntry> page = evidence.range(cursor, (int) Math.min(PAGE, last - cursor));
            if (page.isEmpty()) {
                break;
            }
            for (EvidenceEntry entry : page) {
                items.add(new EvidencePackage.Item(entry.link(), selected(entry, request, from, to) ? entry : null));
            }
            cursor = page.getLast().sequence();
        }
        return items;
    }

    private static boolean selected(EvidenceEntry entry, PackageRequest request, long from, long to) {
        return entry.sequence() >= from && entry.sequence() <= to
                && (request.agentId() == null || request.agentId().equals(entry.agentId()))
                && (request.principalId() == null || request.principalId().equals(entry.principalId()));
    }
}
