package com.nexusphere.ledger.e2e.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public final class FakeLanguageModel implements AutoCloseable {

    public record Request(String authorization, JsonNode body) {

        public boolean wantsJson() {
            return body.path("response_format").path("type").asString("").equals("json_object");
        }

        public String system() {
            return body.path("messages").get(0).path("content").asString();
        }

        public String user() {
            return body.path("messages").get(1).path("content").asString();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, String> plan = request -> "{}";
    private volatile Function<Request, String> answer = request -> "";
    private volatile int status = 200;

    private FakeLanguageModel(HttpServer server) {
        this.server = server;
    }

    public static FakeLanguageModel start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeLanguageModel model = new FakeLanguageModel(server);
            server.createContext("/v1/chat/completions", model::handle);
            server.start();
            return model;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public void plans(Function<Request, String> plan) {
        this.plan = plan;
    }

    public void answers(Function<Request, String> answer) {
        this.answer = answer;
    }

    public void failWith(int status) {
        this.status = status;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Request request = new Request(exchange.getRequestHeaders().getFirst("Authorization"),
                    JSON.readTree(exchange.getRequestBody().readAllBytes()));
            requests.add(request);
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            String content = request.wantsJson() ? plan.apply(request) : answer.apply(request);
            byte[] body = JSON.writeValueAsBytes(Map.of("id", "chatcmpl-1", "object", "chat.completion",
                    "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                            "message", Map.of("role", "assistant", "content", content)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public static String json(Map<String, ?> value) {
        return new String(JSON.writeValueAsBytes(value), StandardCharsets.UTF_8);
    }
}
