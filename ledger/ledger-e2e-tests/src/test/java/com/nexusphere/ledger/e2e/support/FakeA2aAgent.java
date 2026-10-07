package com.nexusphere.ledger.e2e.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
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
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        if ("message/send".equals(request.path("method").asString())) {
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
}
