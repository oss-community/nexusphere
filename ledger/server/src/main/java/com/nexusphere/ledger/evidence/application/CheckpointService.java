package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.transparency.application.TransparencyLog;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CheckpointService {

    public static final int MAX_PAGE_SIZE = 500;

    private final EvidenceRepository evidence;
    private final CheckpointRepository checkpoints;
    private final LedgerSigner signer;
    private final TransparencyLog log;
    private final Clock clock;

    CheckpointService(EvidenceRepository evidence, CheckpointRepository checkpoints, LedgerSigner signer,
                      TransparencyLog log, Clock clock) {
        this.evidence = evidence;
        this.checkpoints = checkpoints;
        this.signer = signer;
        this.log = log;
        this.clock = clock;
    }

    @Transactional
    public Optional<SignedCheckpoint> checkpointHead() {
        LedgerHead head = evidence.lockHead();
        if (head.sequence() == 0) {
            return Optional.empty();
        }
        log.checkpoint(head.sequence(), clock.instant());
        Optional<SignedCheckpoint> existing = checkpoints.findBySequence(head.sequence());
        if (existing.isPresent()) {
            return existing;
        }
        SignedCheckpoint signed = signer.sign(head.sequence(), head.hash(), clock.instant());
        checkpoints.append(signed);
        return Optional.of(signed);
    }

    @Transactional
    public SignedCheckpoint create() {
        return checkpointHead().orElseThrow(() -> new LedgerException(HttpStatus.CONFLICT, "LEDGER_EMPTY",
                "The ledger has no evidence to checkpoint yet.", Map.of()));
    }

    @Transactional(readOnly = true)
    public SignedCheckpoint latest() {
        return checkpoints.latest().orElseThrow(() -> LedgerException.notFound("A checkpoint"));
    }

    @Transactional(readOnly = true)
    public List<SignedCheckpoint> list(long afterSequence, int limit) {
        Paging.check(afterSequence, limit, MAX_PAGE_SIZE);
        return checkpoints.list(afterSequence, limit);
    }
}
