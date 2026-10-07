package com.nexusphere.ledger.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class DemoMcpServer {

    static final String PROTOCOL_VERSION = "2025-06-18";

    private final JsonMapper json = JsonMapper.builder().build();
    private final DemoTools tools = new DemoTools(json);
    private final HttpServer server;

    DemoMcpServer(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/mcp", this::handle);
        server.createContext("/health", exchange -> send(exchange, 200, "{\"status\":\"UP\"}".getBytes()));
    }

    public static void main(String[] args) throws IOException {
        String host = System.getenv().getOrDefault("DEMO_MCP_HOST", "0.0.0.0");
        int port = Integer.parseInt(System.getenv().getOrDefault("DEMO_MCP_PORT", "8091"));
        DemoMcpServer demo = new DemoMcpServer(host, port);
        demo.start();
        System.out.println("Demo MCP server listening on http://" + host + ":" + demo.port() + "/mcp");
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(0);
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "POST" -> post(exchange);
            case "DELETE" -> send(exchange, 200, new byte[0]);
            default -> send(exchange, 405, new byte[0]);
        }
    }

    private void post(HttpExchange exchange) throws IOException {
        JsonNode request;
        try {
            request = json.readTree(exchange.getRequestBody().readAllBytes());
        } catch (JacksonException e) {
            send(exchange, 400, json.writeValueAsBytes(error(null, -32700, "Parse error")));
            return;
        }
        if (request == null || !request.isObject()) {
            send(exchange, 400, json.writeValueAsBytes(error(null, -32600, "Invalid request")));
            return;
        }
        if (!request.has("id")) {
            send(exchange, 202, new byte[0]);
            return;
        }
        JsonNode id = request.get("id");
        ObjectNode response = switch (request.path("method").asString("")) {
            case "initialize" -> {
                exchange.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());
                ObjectNode result = json.createObjectNode();
                result.put("protocolVersion", PROTOCOL_VERSION);
                result.putObject("capabilities").putObject("tools");
                result.putObject("serverInfo").put("name", "nexusphere-ledger-demo").put("version", "0.1.0");
                yield success(id, result);
            }
            case "ping" -> success(id, json.createObjectNode());
            case "tools/list" -> {
                ObjectNode result = json.createObjectNode();
                result.set("tools", tools.list());
                yield success(id, result);
            }
            case "tools/call" -> {
                ObjectNode result = tools.call(request.path("params").path("name").asString(""),
                        request.path("params").path("arguments"));
                yield result == null ? error(id, -32602, "Unknown tool") : success(id, result);
            }
            default -> error(id, -32601, "Method not found");
        };
        send(exchange, 200, json.writeValueAsBytes(response));
    }

    private ObjectNode success(JsonNode id, ObjectNode result) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? json.nullNode() : id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body.length > 0) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
