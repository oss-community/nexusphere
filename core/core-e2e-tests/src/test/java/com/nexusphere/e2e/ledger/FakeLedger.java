package com.nexusphere.e2e.ledger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

final class FakeLedger {

    record Call(String path, String authorization, JsonNode body) {
    }

    static final String API_KEY = "core-ledger-key";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile int evidenceStatus = 201;

    FakeLedger() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void evidenceStatus(int status) {
        evidenceStatus = status;
    }

    List<Call> calls(String path) {
        return calls.stream().filter(call -> call.path().equals(path)).toList();
    }

    List<JsonNode> evidence() {
        List<JsonNode> items = new ArrayList<>();
        for (Call call : calls) {
            if (call.path().equals("/api/v1/evidence")) {
                items.add(call.body());
            } else if (call.path().equals("/api/v1/evidence/batch")) {
                call.body().path("items").forEach(items::add);
            }
        }
        return items;
    }

    List<Call> callsEndingWith(String suffix) {
        return calls.stream().filter(call -> call.path().endsWith(suffix)).toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + API_KEY).equals(authorization)) {
            respond(exchange, 401, "{\"code\":\"UNAUTHENTICATED\"}");
            return;
        }
        if (path.startsWith("/api/v1/evidence") && evidenceStatus != 201) {
            respond(exchange, evidenceStatus, "{\"code\":\"REFUSED\"}");
            return;
        }
        calls.add(new Call(path, authorization, body));
        if (path.equals("/api/v1/grants")) {
            respond(exchange, 201, "{\"id\":\"" + UUID.randomUUID() + "\",\"status\":\"ACTIVE\"}");
        } else if (path.equals("/api/v1/mandates")) {
            respond(exchange, 201, "{\"id\":\"" + UUID.randomUUID() + "\",\"grantId\":\""
                    + body.path("grantId").asString() + "\",\"audience\":\"" + body.path("audience").asString()
                    + "\",\"token\":\"mandate-token\"}");
        } else if (path.endsWith("/revoke")) {
            respond(exchange, 200, "{\"status\":\"REVOKED\"}");
        } else {
            respond(exchange, 201, "{}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
