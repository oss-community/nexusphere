package com.nexusphere.ledger.verifier;

import java.util.List;

public record PackageReport(
        boolean valid,
        String keyId,
        boolean keyPinned,
        Long anchorSequence,
        long checkpointSequence,
        String checkpointCreatedAt,
        long firstSequence,
        long checkedLinks,
        long disclosedEntries,
        String agentId,
        String principalId,
        List<String> problems) {
}
