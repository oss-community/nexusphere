package com.nexusphere.ledger.chain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
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
        SortedMap<String, String> salts,
        SortedMap<String, String> commitments,
        String previousHash,
        String hash) {

    public static final String FORMAT = "nexusphere-ledger/evidence/v2";
    public static final String ATTRIBUTE = "attributes.";

    private static final SecureRandom RANDOM = new SecureRandom();

    public EvidenceEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(previousHash, "previousHash");
        occurredAt = Timestamps.normalize(occurredAt);
        recordedAt = Timestamps.normalize(recordedAt);
        attributes = Collections.unmodifiableSortedMap(new TreeMap<>(attributes == null ? Map.of() : attributes));
        if (salts != null) {
            salts = Collections.unmodifiableSortedMap(new TreeMap<>(salts));
            commitments = commit(personal(principalId, target, reason, correlationId, attributes), salts);
        } else {
            if (principalId != null || target != null || reason != null || correlationId != null
                    || !attributes.isEmpty()) {
                throw new IllegalArgumentException("An erased entry has no personal values");
            }
            commitments = Collections.unmodifiableSortedMap(new TreeMap<>(Objects.requireNonNull(commitments,
                    "commitments")));
        }
    }

    public EvidenceEntry(UUID id, long sequence, Instant occurredAt, Instant recordedAt, String agentId,
                         String principalId, String action, String target, String decision, String reason,
                         String delegationId, String inputHash, String outputHash, String outcome,
                         String correlationId, SortedMap<String, String> attributes, String previousHash,
                         String hash) {
        this(id, sequence, occurredAt, recordedAt, agentId, principalId, action, target, decision, reason,
                delegationId, inputHash, outputHash, outcome, correlationId, attributes,
                newSalts(attributes == null ? Map.of() : attributes), null, previousHash, hash);
    }

    public static SortedMap<String, String> newSalts(Map<String, String> attributes) {
        SortedMap<String, String> salts = new TreeMap<>();
        for (String field : personal(null, null, null, null, attributes).keySet()) {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            salts.put(field, Base64.getUrlEncoder().withoutPadding().encodeToString(salt));
        }
        return salts;
    }

    public static String commitment(String field, String salt, String value) {
        Map<String, Object> opening = new LinkedHashMap<>();
        opening.put("field", field);
        opening.put("salt", salt);
        opening.put("value", value);
        return Hashes.sha256(CanonicalJson.bytes(opening));
    }

    public boolean erased() {
        return salts == null;
    }

    public EvidenceEntry erase() {
        return new EvidenceEntry(id, sequence, occurredAt, recordedAt, agentId, null, action, null, decision, null,
                delegationId, inputHash, outputHash, outcome, null, null, null, commitments, previousHash, hash);
    }

    public EvidenceEntry sealed() {
        return new EvidenceEntry(id, sequence, occurredAt, recordedAt, agentId, principalId, action, target, decision,
                reason, delegationId, inputHash, outputHash, outcome, correlationId, attributes, salts, commitments,
                previousHash, computeHash());
    }

    public String computeHash() {
        return EvidenceLink.hashOf(sequence, previousHash, contentHash());
    }

    public String contentHash() {
        return Hashes.sha256(CanonicalJson.bytes(canonicalContent()));
    }

    public EvidenceLink link() {
        return new EvidenceLink(sequence, previousHash, contentHash(), hash);
    }

    public Map<String, Object> canonicalContent() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", FORMAT);
        content.put("id", id.toString());
        content.put("occurredAt", Timestamps.format(occurredAt));
        content.put("recordedAt", Timestamps.format(recordedAt));
        content.put("agentId", agentId);
        content.put("action", action);
        content.put("decision", decision);
        content.put("delegationId", delegationId);
        content.put("inputHash", inputHash);
        content.put("outputHash", outputHash);
        content.put("outcome", outcome);
        content.put("commitments", commitments);
        return content;
    }

    private static SortedMap<String, String> personal(String principalId, String target, String reason,
                                                      String correlationId, Map<String, String> attributes) {
        SortedMap<String, String> values = new TreeMap<>();
        values.put("principalId", principalId);
        values.put("target", target);
        values.put("reason", reason);
        values.put("correlationId", correlationId);
        attributes.forEach((name, value) -> values.put(ATTRIBUTE + name, value));
        return values;
    }

    private static SortedMap<String, String> commit(SortedMap<String, String> values, SortedMap<String, String> salts) {
        if (!salts.keySet().equals(values.keySet())) {
            throw new IllegalArgumentException("The salts must cover exactly " + values.keySet());
        }
        SortedMap<String, String> commitments = new TreeMap<>();
        values.forEach((field, value) -> commitments.put(field, commitment(field, salts.get(field), value)));
        return Collections.unmodifiableSortedMap(commitments);
    }
}
