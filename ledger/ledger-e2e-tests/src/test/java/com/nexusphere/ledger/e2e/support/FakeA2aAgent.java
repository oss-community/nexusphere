package com.nexusphere.ledger.e2e.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeA2aAgent {

    public static final String AUTHORIZATION = "Bearer sales-agent-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile String lastAuthorization;
    private volatile byte[] lastBody;

    private FakeA2aAgent(HttpServer server) {
        this.server = server;
    }

    public static FakeA2aAgent start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeA2aAgent fake = new FakeA2aAgent(http);
            http.createContext("/a2a", fake::handle);
            http.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/a2a";
    }

    public int calls() {
        return calls.get();
    }

    public String lastAuthorization() {
        return lastAuthorization;
    }

    public byte[] lastBody() {
        return lastBody;
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        lastBody = exchange.getRequestBody().readAllBytes();
        JsonNode request = JSON.readTree(lastBody);
        String method = request.path("method").asString();
        if ("message/stream".equals(method) || "tasks/resubscribe".equals(method)) {
            stream(exchange, request.get("id"), "message/stream".equals(method) ? "completed" : "failed");
            return;
        }
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        if ("message/send".equals(method)) {
            ObjectNode result = response.putObject("result");
            result.put("kind", "message");
            result.put("role", "agent");
            result.put("messageId", "reply-" + request.path("id").asString());
            result.putArray("parts").addObject().put("kind", "text").put("text", "Order accepted");
        } else {
            response.putObject("error").put("code", -32001).put("message", "Task not found");
        }
        byte[] body = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void stream(HttpExchange exchange, JsonNode id, String finalState) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        for (String state : new String[] {"submitted", "working", finalState}) {
            ObjectNode event = JSON.createObjectNode();
            event.put("jsonrpc", "2.0");
            event.set("id", id);
            ObjectNode result = event.putObject("result");
            result.put("kind", "status-update");
            result.put("taskId", "task-1");
            result.put("contextId", "context-1");
            result.putObject("status").put("state", state);
            result.put("final", state.equals(finalState));
            exchange.getResponseBody().write(("data: " + JSON.writeValueAsString(event) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        }
        exchange.close();
    }
}
