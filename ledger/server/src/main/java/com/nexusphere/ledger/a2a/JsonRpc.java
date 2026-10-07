package com.nexusphere.ledger.a2a;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
class JsonRpc {

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int INVALID_PARAMS = -32602;
    static final int UPSTREAM_UNAVAILABLE = -32002;
    static final int DENIED = -32003;
    static final int UNSUPPORTED = -32004;

    static final Set<String> STREAMING = Set.of("message/stream", "tasks/resubscribe");

    private final JsonMapper json;

    JsonRpc(JsonMapper json) {
        this.json = json;
    }

    Optional<JsonNode> read(byte[] body) {
        try {
            JsonNode message = json.readTree(body);
            return message != null && message.isObject() ? Optional.of(message) : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    A2aResponse invalid(byte[] body) {
        Optional<JsonNode> message = read(body);
        if (message.isEmpty()) {
            return error(400, null, PARSE_ERROR, "The request is not one JSON-RPC message.", null, Map.of());
        }
        JsonNode m = message.get();
        if (!m.path("method").isString() || m.get("id") == null || m.get("id").isNull()) {
            return error(400, m.get("id"), INVALID_REQUEST, "A2A requests need a method and an id.", null, Map.of());
        }
        if (STREAMING.contains(m.path("method").asString())) {
            return error(400, m.get("id"), UNSUPPORTED, "Streaming is not supported by the ledger gateway.", null,
                    Map.of());
        }
        return null;
    }

    boolean failed(int status, byte[] body) {
        if (status < 200 || status >= 300) {
            return true;
        }
        return read(body).map(m -> m.has("error")).orElse(true);
    }

    A2aResponse error(int status, JsonNode id, int code, String message, Map<String, ?> data,
                      Map<String, String> headers) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? json.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (data != null && !data.isEmpty()) {
            error.set("data", json.valueToTree(data));
        }
        return new A2aResponse(status, json.writeValueAsBytes(response), headers);
    }
}
