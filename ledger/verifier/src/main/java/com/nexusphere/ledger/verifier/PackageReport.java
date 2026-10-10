package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.Checkpoint;

import java.util.List;

public record PackageReport(
        boolean valid,
        String keyId,
        String pinnedKeyId,
        Long anchorSequence,
        long checkpointSequence,
        String checkpointCreatedAt,
        List<Checkpoint.Profile> complianceProfiles,
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
        List<String> revokedKeys,
        List<String> problems) {
}
