package com.nexusphere.ledger.mcp;

import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.web.EventStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
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
    static final String LAST_EVENT_HEADER = "Last-Event-ID";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper json;

    McpUpstream(JsonMapper json) {
        this.json = json;
    }

    UpstreamResponse post(LedgerProperties.Mcp.Server server, Duration timeout, String sessionId,
                          String protocolVersion, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder request = request(server, timeout, sessionId, protocolVersion)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return open(request.build());
    }

    UpstreamResponse get(LedgerProperties.Mcp.Server server, Duration timeout, String sessionId,
                         String protocolVersion, String lastEventId) throws IOException, InterruptedException {
        HttpRequest.Builder request = request(server, timeout, sessionId, protocolVersion)
                .header("Accept", EventStream.CONTENT_TYPE)
                .GET();
        if (lastEventId != null) {
            request.header(LAST_EVENT_HEADER, lastEventId);
        }
        return open(request.build());
    }

    UpstreamResponse delete(LedgerProperties.Mcp.Server server, Duration timeout, String sessionId,
                            String protocolVersion) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request(server, timeout, sessionId, protocolVersion).DELETE().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new UpstreamResponse(response.statusCode(), null, response.body(), null);
    }

    byte[] answer(InputStream stream, JsonNode id) throws IOException {
        List<String> events = new ArrayList<>();
        try (stream) {
            EventStream.relay(stream, null, event -> {
                if (event.data() != null) {
                    events.add(event.data());
                }
                return false;
            });
        }
        for (String event : events) {
            if (isAnswer(event.getBytes(StandardCharsets.UTF_8), id)) {
                return event.getBytes(StandardCharsets.UTF_8);
            }
        }
        return events.isEmpty() ? new byte[0] : events.getLast().getBytes(StandardCharsets.UTF_8);
    }

    boolean isAnswer(byte[] data, JsonNode id) {
        try {
            JsonNode message = json.readTree(data);
            return message != null && id != null && id.equals(message.get("id"))
                    && (message.has("result") || message.has("error"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private UpstreamResponse open(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String sessionId = response.headers().firstValue(SESSION_HEADER).orElse(null);
        if (EventStream.isEventStream(response.headers().firstValue("Content-Type").orElse(null))) {
            return new UpstreamResponse(response.statusCode(), sessionId, null, response.body());
        }
        try (InputStream body = response.body()) {
            return new UpstreamResponse(response.statusCode(), sessionId, body.readAllBytes(), null);
        }
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
}
