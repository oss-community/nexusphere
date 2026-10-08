package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.ChainVerification;
import com.nexusphere.ledger.chain.ChainVerifier;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VerificationService {

    static final int PAGE_SIZE = 1000;

    private final EvidenceRepository evidence;
    private final CheckpointRepository checkpoints;
    private final LedgerSigner signer;

    VerificationService(EvidenceRepository evidence, CheckpointRepository checkpoints, LedgerSigner signer) {
        this.evidence = evidence;
        this.checkpoints = checkpoints;
        this.signer = signer;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VerificationReport verify() {
        LedgerHead head = evidence.head();
        ChainVerifier verifier = new ChainVerifier();
        long checkedCheckpoints = 0;
        Long latestCheckpoint = null;
        long after = 0;
        while (after < head.sequence()) {
            List<EvidenceEntry> page = evidence.range(after, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            Map<Long, String> hashes = new HashMap<>();
            for (EvidenceEntry entry : page) {
                if (!verifier.accept(entry)) {
                    return broken(verifier.result(), head, checkedCheckpoints, latestCheckpoint);
                }
                hashes.put(entry.sequence(), entry.hash());
            }
            long last = page.getLast().sequence();
            for (SignedCheckpoint signed : checkpoints.between(after, last)) {
                long sequence = signed.checkpoint().sequence();
                String keyId = signed.checkpoint().keyId();
                if (!signer.find(keyId).map(signed::verify).orElse(false)) {
                    return checkpointFailure(verifier.result(), head, checkedCheckpoints, latestCheckpoint, sequence,
                            "checkpoint signature is not valid for key " + keyId);
                }
                if (!signed.checkpoint().headHash().equals(hashes.get(sequence))) {
                    return checkpointFailure(verifier.result(), head, checkedCheckpoints, latestCheckpoint, sequence,
                            "checkpoint hash does not match the evidence at this sequence");
                }
                checkedCheckpoints++;
                latestCheckpoint = sequence;
            }
            after = last;
        }
        ChainVerification chain = verifier.result();
        if (chain.lastSequence() != head.sequence() || !chain.lastHash().equals(head.hash())) {
            return new VerificationReport(false, chain.checkedEntries(), checkedCheckpoints, head.sequence(),
                    head.hash(), latestCheckpoint, chain.lastSequence() + 1,
                    "the ledger head does not match the last evidence in the chain");
        }
        if (!checkpoints.between(head.sequence(), Long.MAX_VALUE).isEmpty()) {
            return new VerificationReport(false, chain.checkedEntries(), checkedCheckpoints, head.sequence(),
                    head.hash(), latestCheckpoint, head.sequence() + 1,
                    "a checkpoint refers to evidence that is not in the chain");
        }
        return new VerificationReport(true, chain.checkedEntries(), checkedCheckpoints, head.sequence(), head.hash(),
                latestCheckpoint, null, null);
    }

    private static VerificationReport broken(ChainVerification chain, LedgerHead head, long checkedCheckpoints,
                                             Long latestCheckpoint) {
        return new VerificationReport(false, chain.checkedEntries(), checkedCheckpoints, head.sequence(), head.hash(),
                latestCheckpoint, chain.failedSequence(), chain.failure());
    }

    private static VerificationReport checkpointFailure(ChainVerification chain, LedgerHead head,
                                                        long checkedCheckpoints, Long latestCheckpoint,
                                                        long sequence, String failure) {
        return new VerificationReport(false, chain.checkedEntries(), checkedCheckpoints, head.sequence(), head.hash(),
                latestCheckpoint, sequence, failure);
    }
}
