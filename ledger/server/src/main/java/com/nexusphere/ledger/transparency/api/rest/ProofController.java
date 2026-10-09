package com.nexusphere.ledger.transparency.api.rest;

import com.nexusphere.ledger.evidence.application.EvidenceService;
import com.nexusphere.ledger.server.web.LedgerException;
import com.nexusphere.ledger.transparency.application.TransparencyLog;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/log/proofs")
class ProofController {

    record InclusionProof(long sequence, long treeSize, String rootHash, List<String> hashes) {
    }

    record ConsistencyProof(long firstSize, long secondSize, String firstRootHash, String secondRootHash,
                            List<String> hashes) {
    }

    private final TransparencyLog log;
    private final EvidenceService evidence;

    ProofController(TransparencyLog log, EvidenceService evidence) {
        this.log = log;
        this.evidence = evidence;
    }

    @GetMapping("/inclusion")
    @Transactional(readOnly = true)
    InclusionProof inclusion(@RequestParam long sequence, @RequestParam(required = false) Long treeSize) {
        long size = treeSize == null ? latestSize() : treeSize;
        if (sequence < 1 || sequence > size || size > evidence.head().sequence()) {
            throw LedgerException.invalid("The proof request has invalid fields.",
                    Map.of("sequence", "must be between 1 and the tree size, which must not exceed the ledger head"));
        }
        return new InclusionProof(sequence, size, encode(log.root(size)),
                log.inclusionProof(sequence - 1, size).stream().map(ProofController::encode).toList());
    }

    @GetMapping("/consistency")
    @Transactional(readOnly = true)
    ConsistencyProof consistency(@RequestParam long firstSize, @RequestParam(required = false) Long secondSize) {
        long second = secondSize == null ? latestSize() : secondSize;
        if (firstSize < 1 || firstSize > second || second > evidence.head().sequence()) {
            throw LedgerException.invalid("The proof request has invalid fields.",
                    Map.of("firstSize", "must be between 1 and the second size, which must not exceed the ledger head"));
        }
        return new ConsistencyProof(firstSize, second, encode(log.root(firstSize)), encode(log.root(second)),
                log.consistencyProof(firstSize, second).stream().map(ProofController::encode).toList());
    }

    private long latestSize() {
        return log.latest().orElseThrow(() -> LedgerException.notFound("A log checkpoint")).size();
    }

    private static String encode(byte[] hash) {
        return Base64.getEncoder().encodeToString(hash);
    }
}
