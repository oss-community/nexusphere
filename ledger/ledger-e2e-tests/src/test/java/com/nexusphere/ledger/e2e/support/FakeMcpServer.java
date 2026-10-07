package com.nexusphere.ledger.e2e.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeMcpServer {

    public static final String SESSION_ID = "fake-session-1";
    public static final String AUTHORIZATION = "Bearer upstream-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private volatile String lastSessionId;
    private volatile String lastAuthorization;

    private FakeMcpServer(HttpServer server) {
        this.server = server;
    }

    public static FakeMcpServer start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeMcpServer fake = new FakeMcpServer(http);
            http.createContext("/mcp", fake::handle);
            http.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/mcp";
    }

    public int calls(String tool) {
        AtomicInteger count = calls.get(tool);
        return count == null ? 0 : count.get();
    }

    public String lastSessionId() {
        return lastSessionId;
    }

    public String lastAuthorization() {
        return lastAuthorization;
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastSessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        if ("DELETE".equals(exchange.getRequestMethod())) {
            send(exchange, 200, "application/json", new byte[0]);
            return;
        }
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String method = request.path("method").asString();
        if (!request.has("id")) {
            send(exchange, 202, "application/json", new byte[0]);
            return;
        }
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        switch (method) {
            case "initialize" -> {
                exchange.getResponseHeaders().set("Mcp-Session-Id", SESSION_ID);
                ObjectNode result = response.putObject("result");
                result.put("protocolVersion", "2025-06-18");
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "fake").put("version", "1.0.0");
            }
            case "tools/list" -> {
                ObjectNode result = response.putObject("result");
                result.putArray("tools").addObject().put("name", "echo");
            }
            case "tools/call" -> {
                String tool = request.path("params").path("name").asString();
                calls.computeIfAbsent(tool, t -> new AtomicInteger()).incrementAndGet();
                switch (tool) {
                    case "crash" -> response.putObject("error").put("code", -32603).put("message", "Tool crashed");
                    case "fail" -> {
                        ObjectNode result = response.putObject("result");
                        result.putArray("content").addObject().put("type", "text").put("text", "failed");
                        result.put("isError", true);
                    }
                    default -> {
                        ObjectNode result = response.putObject("result");
                        result.putArray("content").addObject().put("type", "text")
                                .put("text", JSON.writeValueAsString(request.path("params").path("arguments")));
                    }
                }
                if (tool.endsWith("_sse")) {
                    String events = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\","
                            + "\"params\":{\"progress\":1}}\n\nevent: message\ndata: "
                            + JSON.writeValueAsString(response) + "\n\n";
                    send(exchange, 200, "text/event-stream", events.getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            default -> response.putObject("error").put("code", -32601).put("message", "Method not found");
        }
        send(exchange, 200, "application/json", JSON.writeValueAsBytes(response));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
