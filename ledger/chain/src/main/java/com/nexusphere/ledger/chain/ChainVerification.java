package com.nexusphere.ledger.chain;

public record ChainVerification(
        boolean valid,
        long checkedEntries,
        long lastSequence,
        String lastHash,
        Long failedSequence,
        String failure) {

    static ChainVerification valid(long checkedEntries, long lastSequence, String lastHash) {
        return new ChainVerification(true, checkedEntries, lastSequence, lastHash, null, null);
    }

    static ChainVerification broken(long checkedEntries, long lastSequence, String lastHash, long failedSequence,
                                    String failure) {
        return new ChainVerification(false, checkedEntries, lastSequence, lastHash, failedSequence, failure);
    }
}
