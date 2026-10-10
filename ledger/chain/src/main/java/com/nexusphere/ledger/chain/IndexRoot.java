package com.nexusphere.ledger.chain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record IndexRoot(long sequence, String headHash, String root, Instant createdAt, String keyId) {

    public static final String FORMAT = "nexusphere-ledger/index/v1";
    public static final Map<String, List<String>> TERMS = Map.of(
            "agent", List.of("agentId"),
            "action", List.of("agentId", "action"),
            "input", List.of("inputHash"),
            "grant", List.of("delegationId"));

    public IndexRoot {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(keyId, "keyId");
        if (!Hashes.isSha256(headHash) || !Hashes.isSha256(root)) {
            throw new IllegalArgumentException("headHash and root must be lowercase hex SHA-256");
        }
        createdAt = Timestamps.normalize(createdAt);
    }

    public byte[] signedBytes() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", FORMAT);
        content.put("sequence", sequence);
        content.put("headHash", headHash);
        content.put("root", root);
        content.put("createdAt", Timestamps.format(createdAt));
        content.put("keyId", keyId);
        return CanonicalJson.bytes(content);
    }

    public static List<Map<String, Object>> terms(String agentId, String action, String decision, String inputHash,
                                                  String delegationId) {
        List<Map<String, Object>> terms = new ArrayList<>();
        terms.add(term("agent", "agentId", agentId));
        Map<String, Object> agentAction = term("action", "agentId", agentId);
        agentAction.put("action", action);
        terms.add(agentAction);
        if (inputHash != null) {
            terms.add(term("input", "inputHash", inputHash));
        }
        if (delegationId != null && "ALLOW".equals(decision)) {
            terms.add(term("grant", "delegationId", delegationId));
        }
        return terms;
    }

    public static Map<String, Object> value(long count, long firstSequence, long lastSequence) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("count", count);
        value.put("firstSequence", firstSequence);
        value.put("lastSequence", lastSequence);
        return value;
    }

    public static Map<String, Object> term(Map<String, ?> requested) {
        Object type = requested.get("type");
        List<String> fields = TERMS.get(type instanceof String name ? name : "");
        if (fields == null) {
            throw new IllegalArgumentException("type must be one of agent, action, input or grant");
        }
        if (requested.size() != fields.size() + 1) {
            throw new IllegalArgumentException("a " + type + " term has only type and " + String.join(", ", fields));
        }
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("type", type);
        for (String field : fields) {
            if (!(requested.get(field) instanceof String value) || value.isEmpty()) {
                throw new IllegalArgumentException(field + " is required for a " + type + " term");
            }
            term.put(field, value);
        }
        return term;
    }

    private static Map<String, Object> term(String type, String field, String value) {
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("type", type);
        term.put(field, value);
        return term;
    }
}
