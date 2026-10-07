package com.nexusphere.ledger.evidence.domain.model;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.chain.EvidenceLink;
import com.nexusphere.ledger.chain.SignedCheckpoint;

import java.time.Instant;
import java.util.List;

public record EvidencePackage(
        Instant createdAt,
        String agentId,
        String principalId,
        long fromSequence,
        long toSequence,
        SignedCheckpoint anchor,
        SignedCheckpoint checkpoint,
        List<Item> items) {

    public static final String FORMAT = "nexusphere-ledger/package/v1";

    public record Item(EvidenceLink link, EvidenceEntry entry) {
    }

    public long disclosed() {
        return items.stream().filter(item -> item.entry() != null).count();
    }
}
