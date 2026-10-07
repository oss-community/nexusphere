package com.nexusphere.ledger.chain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

public record EvidenceEntry(
        UUID id,
        long sequence,
        Instant occurredAt,
        Instant recordedAt,
        String agentId,
        String principalId,
        String action,
        String target,
        String decision,
        String reason,
        String delegationId,
        String inputHash,
        String outputHash,
        String outcome,
        String correlationId,
        SortedMap<String, String> attributes,
        String previousHash,
        String hash) {

    public static final String FORMAT = "nexusphere-ledger/evidence/v1";

    public EvidenceEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(previousHash, "previousHash");
        occurredAt = Timestamps.normalize(occurredAt);
        recordedAt = Timestamps.normalize(recordedAt);
        attributes = Collections.unmodifiableSortedMap(new TreeMap<>(attributes == null ? Map.of() : attributes));
    }

    public EvidenceEntry sealed() {
        return new EvidenceEntry(id, sequence, occurredAt, recordedAt, agentId, principalId, action, target, decision,
                reason, delegationId, inputHash, outputHash, outcome, correlationId, attributes, previousHash,
                computeHash());
    }

    public String computeHash() {
        return Hashes.sha256(CanonicalJson.bytes(canonicalContent()));
    }

    public Map<String, Object> canonicalContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", FORMAT);
        content.put("id", id.toString());
        content.put("sequence", sequence);
        content.put("occurredAt", Timestamps.format(occurredAt));
        content.put("recordedAt", Timestamps.format(recordedAt));
        content.put("agentId", agentId);
        content.put("principalId", principalId);
        content.put("action", action);
        content.put("target", target);
        content.put("decision", decision);
        content.put("reason", reason);
        content.put("delegationId", delegationId);
        content.put("inputHash", inputHash);
        content.put("outputHash", outputHash);
        content.put("outcome", outcome);
        content.put("correlationId", correlationId);
        content.put("attributes", attributes);
        content.put("previousHash", previousHash);
        return content;
    }
}
