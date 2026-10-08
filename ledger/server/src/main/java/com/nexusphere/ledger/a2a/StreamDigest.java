package com.nexusphere.ledger.a2a;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

final class StreamDigest {

    static final String RECEIPT_EVENT = "nexusphere-receipt";

    private static final Set<String> FAILED_STATES = Set.of("failed", "rejected");

    private final MessageDigest digest;
    private final JsonRpc rpc;
    private int events;
    private boolean failed;

    StreamDigest(JsonRpc rpc) {
        this.rpc = rpc;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void accept(String data) {
        if (data == null) {
            return;
        }
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        digest.update(bytes);
        digest.update((byte) '\n');
        events++;
        Optional<JsonNode> message = rpc.read(bytes);
        if (message.isEmpty() || message.get().has("error")) {
            failed = true;
            return;
        }
        JsonNode result = message.get().path("result");
        String state = result.path("status").path("state").asString(null);
        if (state != null && FAILED_STATES.contains(state)) {
            failed = true;
        }
    }

    int events() {
        return events;
    }

    boolean failed(int status) {
        return failed || events == 0 || status < 200 || status >= 300;
    }

    String hash() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
