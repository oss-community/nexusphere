package com.nexusphere.ledger.verifier;

import java.util.List;

public record PackageReport(
        boolean valid,
        String keyId,
        String pinnedKeyId,
        Long anchorSequence,
        long checkpointSequence,
        String checkpointCreatedAt,
        long firstSequence,
        long checkedLinks,
        long disclosedEntries,
        String agentId,
        String principalId,
        String logOrigin,
        Long logTreeSize,
        long provenEntries,
        long receiptedEntries,
        List<String> witnesses,
        List<String> problems) {
}
