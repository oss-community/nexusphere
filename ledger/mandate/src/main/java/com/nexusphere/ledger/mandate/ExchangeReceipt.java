package com.nexusphere.ledger.mandate;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ExchangeReceipt(String issuer, String audience, UUID receiptId, UUID requestId, UUID mandateId,
                              String agentId, String requestHash, String responseHash, int status, String outcome,
                              long evidenceSequence, String evidenceHash, Instant issuedAt) {

    public static final String TYPE = "nexusphere-a2a-receipt+jwt";

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("aud", audience);
        payload.put("jti", receiptId.toString());
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("request", requestId.toString());
        payload.put("mandate", mandateId.toString());
        payload.put("agent", agentId);
        payload.put("request_hash", requestHash);
        payload.put("response_hash", responseHash);
        payload.put("status", status);
        payload.put("outcome", outcome);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sequence", evidenceSequence);
        evidence.put("hash", evidenceHash);
        payload.put("evidence", evidence);
        return payload;
    }

    public static ExchangeReceipt fromPayload(JsonNode p) {
        JsonNode e = p.path("evidence");
        return new ExchangeReceipt(Claims.text(p, "iss"), Claims.text(p, "aud"), UUID.fromString(Claims.text(p, "jti")),
                UUID.fromString(Claims.text(p, "request")), UUID.fromString(Claims.text(p, "mandate")),
                Claims.text(p, "agent"), Claims.text(p, "request_hash"), Claims.text(p, "response_hash"),
                (int) Claims.number(p, "status"), Claims.text(p, "outcome"), Claims.number(e, "sequence"),
                Claims.text(e, "hash"), Instant.ofEpochSecond(Claims.number(p, "iat")));
    }
}
