package com.nexusphere.ledger.mandate;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ExchangeRequest(String issuer, String agentId, String audience, UUID requestId, UUID mandateId,
                              String method, String requestHash, Instant issuedAt) {

    public static final String TYPE = "nexusphere-a2a-request+jwt";

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("sub", agentId);
        payload.put("aud", audience);
        payload.put("jti", requestId.toString());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("mandate", mandateId.toString());
        payload.put("method", method);
        payload.put("request_hash", requestHash);
        return payload;
    }

    public static ExchangeRequest fromPayload(JsonNode p) {
        return new ExchangeRequest(Claims.text(p, "iss"), Claims.text(p, "sub"), Claims.text(p, "aud"),
                UUID.fromString(Claims.text(p, "jti")), UUID.fromString(Claims.text(p, "mandate")),
                Claims.text(p, "method"), Claims.text(p, "request_hash"), Instant.ofEpochSecond(Claims.number(p, "iat")));
    }
}
