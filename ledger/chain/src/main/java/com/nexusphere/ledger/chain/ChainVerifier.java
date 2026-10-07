package com.nexusphere.ledger.chain;

public final class ChainVerifier {

    private long expectedSequence;
    private String expectedPreviousHash;
    private long checked;
    private ChainVerification failure;

    public ChainVerifier() {
        this(1, Hashes.GENESIS);
    }

    public ChainVerifier(long firstSequence, String previousHash) {
        this.expectedSequence = firstSequence;
        this.expectedPreviousHash = previousHash;
    }

    public boolean accept(EvidenceEntry entry) {
        if (failure != null) {
            return false;
        }
        if (entry.sequence() != expectedSequence) {
            return fail(entry, "expected sequence " + expectedSequence + " but found " + entry.sequence());
        }
        if (!expectedPreviousHash.equals(entry.previousHash())) {
            return fail(entry, "previous hash does not match the hash of sequence " + (expectedSequence - 1));
        }
        String recomputed = entry.computeHash();
        if (!recomputed.equals(entry.hash())) {
            return fail(entry, "content does not match its hash");
        }
        checked++;
        expectedSequence++;
        expectedPreviousHash = recomputed;
        return true;
    }

    public ChainVerification result() {
        if (failure != null) {
            return failure;
        }
        return ChainVerification.valid(checked, expectedSequence - 1, expectedPreviousHash);
    }

    public static ChainVerification verify(Iterable<EvidenceEntry> entries) {
        ChainVerifier verifier = new ChainVerifier();
        for (EvidenceEntry entry : entries) {
            if (!verifier.accept(entry)) {
                break;
            }
        }
        return verifier.result();
    }

    private boolean fail(EvidenceEntry entry, String reason) {
        failure = ChainVerification.broken(checked, expectedSequence - 1, expectedPreviousHash, entry.sequence(),
                reason);
        return false;
    }
}
