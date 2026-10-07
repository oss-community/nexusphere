package com.nexusphere.ledger.mcp;

import com.nexusphere.ledger.server.config.LedgerProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
class McpUpstream {

    static final String SESSION_HEADER = "Mcp-Session-Id";
    static final String PROTOCOL_HEADER = "MCP-Protocol-Version";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json;

    McpUpstream(JsonMapper json) {
        this.json = json;
    }

    UpstreamResponse post(LedgerProperties.Mcp.Server server, Duration timeout, String sessionId,
                          String protocolVersion, byte[] body, JsonNode id) throws IOException, InterruptedException {
        HttpRequest.Builder request = request(server, timeout, sessionId, protocolVersion)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        byte[] payload = contentType.startsWith("text/event-stream") ? fromEvents(response.body(), id)
                : response.body();
        return new UpstreamResponse(response.statusCode(),
                response.headers().firstValue(SESSION_HEADER).orElse(null), payload);
    }

    UpstreamResponse delete(LedgerProperties.Mcp.Server server, Duration timeout, String sessionId,
                            String protocolVersion) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request(server, timeout, sessionId, protocolVersion).DELETE().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new UpstreamResponse(response.statusCode(), null, response.body());
    }

    private static HttpRequest.Builder request(LedgerProperties.Mcp.Server server, Duration timeout,
                                               String sessionId, String protocolVersion) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(server.url()).timeout(timeout);
        if (server.authorization() != null && !server.authorization().isBlank()) {
            builder.header("Authorization", server.authorization());
        }
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        if (protocolVersion != null) {
            builder.header(PROTOCOL_HEADER, protocolVersion);
        }
        return builder;
    }

    private byte[] fromEvents(byte[] stream, JsonNode id) {
        List<String> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : new String(stream, StandardCharsets.UTF_8).split("\r?\n", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    events.add(data.toString());
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
        }
        if (!data.isEmpty()) {
            events.add(data.toString());
        }
        for (String event : events) {
            JsonNode message = json.readTree(event);
            if (id != null && id.equals(message.get("id")) && (message.has("result") || message.has("error"))) {
                return event.getBytes(StandardCharsets.UTF_8);
            }
        }
        return events.isEmpty() ? new byte[0] : events.getLast().getBytes(StandardCharsets.UTF_8);
    }
}
