package com.nexusphere.ledger.evidence.application;

public record VerificationReport(
        boolean valid,
        long checkedEntries,
        long checkedCheckpoints,
        long headSequence,
        String headHash,
        Long latestCheckpointSequence,
        Long failedSequence,
        String failure) {
}
