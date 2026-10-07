package com.nexusphere.ledger.chain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record Checkpoint(long sequence, String headHash, Instant createdAt, String keyId) {

    public static final String FORMAT = "nexusphere-ledger/checkpoint/v1";

    public Checkpoint {
        Objects.requireNonNull(headHash, "headHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(keyId, "keyId");
        createdAt = Timestamps.normalize(createdAt);
    }

    public byte[] signedBytes() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", FORMAT);
        content.put("sequence", sequence);
        content.put("headHash", headHash);
        content.put("createdAt", Timestamps.format(createdAt));
        content.put("keyId", keyId);
        return CanonicalJson.bytes(content);
    }
}
