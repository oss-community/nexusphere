package com.nexusphere.ledger.evidence.application;

import com.nexusphere.ledger.chain.ChainVerification;
import com.nexusphere.ledger.chain.ChainVerifier;
import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.evidence.domain.model.LedgerHead;
import com.nexusphere.ledger.evidence.domain.repository.CheckpointRepository;
import com.nexusphere.ledger.evidence.domain.repository.EvidenceRepository;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.transparency.application.TransparencyLog;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository;
import com.nexusphere.ledger.transparency.domain.repository.LogCheckpointRepository.StoredCheckpoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VerificationService {

    static final int PAGE_SIZE = 1000;

    private final EvidenceRepository evidence;
    private final CheckpointRepository checkpoints;
    private final LedgerSigner signer;
    private final TransparencyLog log;
    private final LogCheckpointRepository logCheckpoints;

    VerificationService(EvidenceRepository evidence, CheckpointRepository checkpoints, LedgerSigner signer,
                        TransparencyLog log, LogCheckpointRepository logCheckpoints) {
        this.evidence = evidence;
        this.checkpoints = checkpoints;
        this.signer = signer;
        this.log = log;
        this.logCheckpoints = logCheckpoints;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VerificationReport verify() {
        LedgerHead head = evidence.head();
        ChainVerifier verifier = new ChainVerifier();
        MerkleTree.Builder tree = new MerkleTree.Builder();
        Deque<StoredCheckpoint> notes = new ArrayDeque<>(logCheckpoints.all());
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
                tree.add(MerkleTree.leafHash(TransparencyLog.leaf(entry)));
                while (!notes.isEmpty() && notes.peekFirst().size() == tree.size()) {
                    String failure = logFailure(notes.removeFirst(), tree.root());
                    if (failure != null) {
                        return checkpointFailure(verifier.result(), head, checkedCheckpoints, latestCheckpoint,
                                entry.sequence(), failure);
                    }
                }
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
        if (!notes.isEmpty()) {
            return new VerificationReport(false, chain.checkedEntries(), checkedCheckpoints, head.sequence(),
                    head.hash(), latestCheckpoint, head.sequence() + 1,
                    "a log checkpoint covers evidence that is not in the chain");
        }
        if (!Arrays.equals(tree.root(), log.root(head.sequence()))) {
            return new VerificationReport(false, chain.checkedEntries(), checkedCheckpoints, head.sequence(),
                    head.hash(), latestCheckpoint, head.sequence(),
                    "the stored Merkle tree does not match the evidence chain");
        }
        if (!checkpoints.between(head.sequence(), Long.MAX_VALUE).isEmpty()) {
            return new VerificationReport(false, chain.checkedEntries(), checkedCheckpoints, head.sequence(),
                    head.hash(), latestCheckpoint, head.sequence() + 1,
                    "a checkpoint refers to evidence that is not in the chain");
        }
        return new VerificationReport(true, chain.checkedEntries(), checkedCheckpoints, head.sequence(), head.hash(),
                latestCheckpoint, null, null);
    }

    private String logFailure(StoredCheckpoint stored, byte[] root) {
        if (!Arrays.equals(stored.root(), root)) {
            return "log checkpoint " + stored.size() + " does not match the Merkle root of the evidence";
        }
        LogCheckpoint.Note note = LogCheckpoint.parse(stored.note());
        if (!note.checkpoint().equals(new LogCheckpoint(log.origin(), stored.size(), root))) {
            return "log checkpoint " + stored.size() + " does not sign the Merkle root of the evidence";
        }
        boolean signed = signer.keys().stream().anyMatch(key -> note.signedBy(
                new NoteKey(note.checkpoint().origin(), NoteKey.ED25519, key.publicKey().publicKey())));
        return signed ? null : "log checkpoint " + stored.size() + " is not signed by a ledger key";
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
