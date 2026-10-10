package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.Hashes;
import tools.jackson.databind.JsonNode;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public record TrackRecordClaims(
        String issuer,
        UUID id,
        String agentId,
        String audience,
        Instant issuedAt,
        Instant expiresAt,
        long checkpointSequence,
        String headHash,
        String indexRoot,
        long total,
        Long firstSequence,
        Long lastSequence,
        Instant firstAt,
        Instant lastAt,
        Map<String, Long> outcomes,
        Long principals,
        Map<String, Long> actions,
        PublicKey holderKey) {

    public static final String TYPE = "dc+sd-jwt";
    public static final String VCT = "urn:nexusphere:vct:agent-track-record:1";
    public static final List<String> SELECTIVE = List.of("outcomes", "principals", "actions");

    public TrackRecordClaims {
        if (!Hashes.isSha256(headHash) || !Hashes.isSha256(indexRoot)) {
            throw new IllegalArgumentException("The track record names no valid head hash or index root");
        }
        outcomes = outcomes == null ? null : Collections.unmodifiableSortedMap(new TreeMap<>(outcomes));
        actions = actions == null ? null : Collections.unmodifiableSortedMap(new TreeMap<>(actions));
    }

    public boolean bound() {
        return holderKey != null;
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("vct", VCT);
        payload.put("sub", agentId);
        if (audience != null) {
            payload.put("aud", audience);
        }
        payload.put("jti", id.toString());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("nbf", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("sequence", checkpointSequence);
        checkpoint.put("headHash", headHash);
        checkpoint.put("indexRoot", indexRoot);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("checkpoint", checkpoint);
        record.put("total", total);
        if (firstSequence != null) {
            record.put("firstSequence", firstSequence);
            record.put("lastSequence", lastSequence);
            record.put("firstAt", firstAt.toString());
            record.put("lastAt", lastAt.toString());
        }
        if (outcomes != null) {
            record.put("outcomes", new TreeMap<>(outcomes));
        }
        if (principals != null) {
            record.put("principals", principals);
        }
        if (actions != null) {
            record.put("actions", new TreeMap<>(actions));
        }
        payload.put("record", record);
        if (holderKey != null) {
            payload.put("cnf", Map.of("jwk", Jwk.confirmation(holderKey)));
        }
        return payload;
    }

    public static TrackRecordClaims fromPayload(JsonNode p) {
        if (!VCT.equals(p.path("vct").asString(null))) {
            throw new IllegalArgumentException("The token is not a " + VCT + " credential");
        }
        JsonNode r = p.path("record");
        JsonNode c = r.path("checkpoint");
        boolean active = r.path("firstSequence").isNumber();
        return new TrackRecordClaims(
                required(p, "iss"),
                UUID.fromString(required(p, "jti")),
                required(p, "sub"),
                p.path("aud").isString() ? p.path("aud").asString() : null,
                Instant.ofEpochSecond(number(p, "iat")),
                Instant.ofEpochSecond(number(p, "exp")),
                number(c, "sequence"),
                required(c, "headHash"),
                required(c, "indexRoot"),
                number(r, "total"),
                active ? number(r, "firstSequence") : null,
                active ? number(r, "lastSequence") : null,
                active ? Instant.parse(required(r, "firstAt")) : null,
                active ? Instant.parse(required(r, "lastAt")) : null,
                counts(r.path("outcomes")),
                r.path("principals").isNumber() ? r.path("principals").asLong() : null,
                counts(r.path("actions")),
                p.path("cnf").isObject() ? Jwk.publicKey(p.path("cnf").path("jwk")) : null);
    }

    private static Map<String, Long> counts(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("The track record has malformed counts");
        }
        Map<String, Long> counts = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            if (!entry.getValue().isIntegralNumber() || entry.getValue().asLong() < 0) {
                throw new IllegalArgumentException("The track record has a malformed count for " + entry.getKey());
            }
            counts.put(entry.getKey(), entry.getValue().asLong());
        }
        return counts;
    }

    private static String required(JsonNode node, String field) {
        if (!node.path(field).isString()) {
            throw new IllegalArgumentException("The track record has no " + field);
        }
        return node.path(field).asString();
    }

    private static long number(JsonNode node, String field) {
        if (!node.path(field).isIntegralNumber()) {
            throw new IllegalArgumentException("The track record has no " + field);
        }
        return node.path(field).asLong();
    }
}
