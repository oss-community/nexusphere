package com.nexusphere.ledger.mandate;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record MandateClaims(
        String issuer,
        UUID mandateId,
        String agentId,
        String principalId,
        String audience,
        List<String> actions,
        List<String> targets,
        Long maxUses,
        UUID grantId,
        String termsHash,
        Instant issuedAt,
        Instant notBefore,
        Instant expiresAt,
        String statusListUrl,
        long statusIndex) {

    public static final String TYPE = "nexusphere-mandate+jwt";

    public MandateClaims {
        actions = List.copyOf(actions);
        targets = List.copyOf(targets);
    }

    public boolean covers(String action, String target) {
        return actions.stream().anyMatch(p -> matches(p, action)) && targets.stream().anyMatch(p -> matches(p, target));
    }

    static boolean matches(String pattern, String value) {
        if (value == null) {
            return "*".equals(pattern);
        }
        return pattern.endsWith("*") ? value.startsWith(pattern.substring(0, pattern.length() - 1))
                : pattern.equals(value);
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("sub", agentId);
        if (audience != null) {
            payload.put("aud", audience);
        }
        payload.put("jti", mandateId.toString());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("nbf", notBefore.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        Map<String, Object> mandate = new LinkedHashMap<>();
        mandate.put("principal", principalId);
        mandate.put("actions", actions);
        mandate.put("targets", targets);
        if (maxUses != null) {
            mandate.put("maxUses", maxUses);
        }
        mandate.put("grant", grantId.toString());
        mandate.put("termsHash", termsHash);
        payload.put("mandate", mandate);
        Map<String, Object> status = new LinkedHashMap<>();
        Map<String, Object> statusList = new LinkedHashMap<>();
        statusList.put("idx", statusIndex);
        statusList.put("uri", statusListUrl);
        status.put("status_list", statusList);
        payload.put("status", status);
        return payload;
    }

    public static MandateClaims fromPayload(JsonNode p) {
        JsonNode m = p.path("mandate");
        JsonNode s = p.path("status").path("status_list");
        return new MandateClaims(
                required(p, "iss"),
                UUID.fromString(required(p, "jti")),
                required(p, "sub"),
                required(m, "principal"),
                p.path("aud").isString() ? p.path("aud").asString() : null,
                strings(m.path("actions")),
                strings(m.path("targets")),
                m.path("maxUses").isNumber() ? m.path("maxUses").asLong() : null,
                UUID.fromString(required(m, "grant")),
                required(m, "termsHash"),
                Instant.ofEpochSecond(number(p, "iat")),
                Instant.ofEpochSecond(number(p, "nbf")),
                Instant.ofEpochSecond(number(p, "exp")),
                required(s, "uri"),
                number(s, "idx"));
    }

    private static String required(JsonNode node, String field) {
        if (!node.path(field).isString()) {
            throw new IllegalArgumentException("The mandate has no " + field);
        }
        return node.path(field).asString();
    }

    private static long number(JsonNode node, String field) {
        if (!node.path(field).isNumber()) {
            throw new IllegalArgumentException("The mandate has no " + field);
        }
        return node.path(field).asLong();
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException("The mandate has no actions or targets");
        }
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }
}
