package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.Signer;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ExchangeStatement(String issuer, String peer, Instant from, Instant to, Instant issuedAt,
                                List<Item> exchanges) {

    public static final String TYPE = "nexusphere-a2a-statement+jwt";
    public static final String OUTBOUND = "OUTBOUND";
    public static final String INBOUND = "INBOUND";
    public static final int MAX_EXCHANGES = 10_000;

    public record Item(String direction, UUID requestId, Instant requestedAt, String method, UUID mandateId,
                       String requestHash, String responseHash, Integer status, String outcome, String receiptHash) {

        public Item {
            if (!OUTBOUND.equals(direction) && !INBOUND.equals(direction)) {
                throw new IllegalArgumentException("The exchange direction must be OUTBOUND or INBOUND");
            }
            if (!Hashes.isSha256(requestHash) || (responseHash != null && !Hashes.isSha256(responseHash))
                    || (receiptHash != null && !Hashes.isSha256(receiptHash))) {
                throw new IllegalArgumentException("The exchange " + requestId + " has a malformed hash");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("direction", direction);
            map.put("requestId", requestId.toString());
            map.put("requestedAt", requestedAt.getEpochSecond());
            map.put("method", method);
            map.put("mandateId", mandateId.toString());
            map.put("requestHash", requestHash);
            map.put("responseHash", responseHash);
            map.put("status", status);
            map.put("outcome", outcome);
            map.put("receiptHash", receiptHash);
            return map;
        }

        static Item of(JsonNode n) {
            return new Item(Claims.text(n, "direction"), UUID.fromString(Claims.text(n, "requestId")),
                    Instant.ofEpochSecond(Claims.number(n, "requestedAt")), Claims.text(n, "method"),
                    UUID.fromString(Claims.text(n, "mandateId")), Claims.text(n, "requestHash"),
                    optional(n, "responseHash"), n.path("status").isIntegralNumber() ? n.path("status").asInt() : null,
                    optional(n, "outcome"), optional(n, "receiptHash"));
        }
    }

    public ExchangeStatement {
        if (exchanges.size() > MAX_EXCHANGES) {
            throw new IllegalArgumentException("A statement holds at most " + MAX_EXCHANGES + " exchanges");
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("The statement window must end after it starts");
        }
        exchanges = exchanges.stream().sorted(Comparator.comparing(Item::requestedAt)
                .thenComparing(Item::requestId)).toList();
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer);
        payload.put("peer", peer);
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("from", from.getEpochSecond());
        payload.put("to", to.getEpochSecond());
        payload.put("exchanges", exchanges.stream().map(Item::toMap).toList());
        return payload;
    }

    public static ExchangeStatement fromPayload(JsonNode p) {
        List<Item> items = new ArrayList<>();
        if (!p.path("exchanges").isArray()) {
            throw new IllegalArgumentException("The token has no exchanges");
        }
        p.path("exchanges").forEach(item -> items.add(Item.of(item)));
        return new ExchangeStatement(Claims.text(p, "iss"), Claims.text(p, "peer"),
                Instant.ofEpochSecond(Claims.number(p, "from")), Instant.ofEpochSecond(Claims.number(p, "to")),
                Instant.ofEpochSecond(Claims.number(p, "iat")), items);
    }

    public String sign(String keyId, Signer key) {
        return Jws.sign(TYPE, keyId, toPayload(), key);
    }

    public static ExchangeStatement parse(String token) {
        return fromPayload(Jws.parse(token).payload());
    }

    public static ExchangeStatement verified(String token, String issuer, KeyResolver keys) {
        return fromPayload(Jws.verified(token, TYPE, issuer, keys).payload());
    }

    private static String optional(JsonNode node, String field) {
        return node.path(field).isString() ? node.path(field).asString() : null;
    }
}
