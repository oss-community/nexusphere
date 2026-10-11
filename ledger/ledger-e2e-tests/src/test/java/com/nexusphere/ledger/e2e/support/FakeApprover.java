package com.nexusphere.ledger.e2e.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class FakeApprover implements AutoCloseable {

    public record Webhook(JsonNode body, Map<String, String> headers) {
    }

    public record BackchannelRequest(String loginHint, String bindingMessage, String authorization) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Webhook> webhooks = new CopyOnWriteArrayList<>();
    private final List<BackchannelRequest> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<String> answers = new ConcurrentLinkedQueue<>();
    private final Map<String, String> pending = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    private FakeApprover(HttpServer server) {
        this.server = server;
    }

    public static FakeApprover start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeApprover fake = new FakeApprover(http);
            http.createContext("/webhook", fake::webhook);
            http.createContext("/bc-authorize", fake::authorize);
            http.createContext("/token", fake::token);
            http.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    public List<Webhook> webhooks() {
        return webhooks;
    }

    public List<BackchannelRequest> requests() {
        return requests;
    }

    public void answerNext(String answer) {
        answers.add(answer);
    }

    private void webhook(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
        webhooks.add(new Webhook(JSON.readTree(exchange.getRequestBody().readAllBytes()), headers));
        send(exchange, 204, null);
    }

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(exchange);
        requests.add(new BackchannelRequest(form.get("login_hint"), form.get("binding_message"),
                exchange.getRequestHeaders().getFirst("Authorization")));
        String id = "request-" + counter.incrementAndGet();
        String answer = answers.poll();
        pending.put(id, answer == null ? "wait" : answer);
        send(exchange, 200, Map.of("auth_req_id", id, "expires_in", 600, "interval", 1));
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = form(exchange);
        String answer = pending.getOrDefault(form.get("auth_req_id"), "unknown");
        if (answer.startsWith("token:")) {
            send(exchange, 200, Map.of("access_token", answer.substring(6), "id_token", answer.substring(6),
                    "token_type", "Bearer"));
        } else if (answer.equals("deny")) {
            send(exchange, 400, Map.of("error", "access_denied"));
        } else if (answer.equals("wait")) {
            send(exchange, 400, Map.of("error", "authorization_pending"));
        } else {
            send(exchange, 400, Map.of("error", "invalid_grant"));
        }
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        Map<String, String> form = new HashMap<>();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void send(HttpExchange exchange, int status, Object body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
