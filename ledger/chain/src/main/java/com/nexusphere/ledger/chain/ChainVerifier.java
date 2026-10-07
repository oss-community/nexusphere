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
        return accept(entry.link());
    }

    public boolean accept(EvidenceLink link) {
        if (failure != null) {
            return false;
        }
        if (link.sequence() != expectedSequence) {
            return fail(link, "expected sequence " + expectedSequence + " but found " + link.sequence());
        }
        if (!expectedPreviousHash.equals(link.previousHash())) {
            return fail(link, "previous hash does not match the hash of sequence " + (expectedSequence - 1));
        }
        String recomputed = link.computeHash();
        if (!recomputed.equals(link.hash())) {
            return fail(link, "content does not match its hash");
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

    private boolean fail(EvidenceLink link, String reason) {
        failure = ChainVerification.broken(checked, expectedSequence - 1, expectedPreviousHash, link.sequence(),
                reason);
        return false;
    }
}
