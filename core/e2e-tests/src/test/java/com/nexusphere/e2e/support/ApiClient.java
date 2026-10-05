package com.nexusphere.e2e.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class ApiClient {

    public record Response(int status, Map<String, String> headers, String body) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase()));
        }
    }

    public static final String OPERATOR_SECRET = "nexusphere-development-operator-secret-change-me";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();

    ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public ApiClient withHeader(String name, String value) {
        ApiClient copy = new ApiClient(baseUrl);
        copy.defaultHeaders.putAll(defaultHeaders);
        copy.defaultHeaders.put(name, value);
        return copy;
    }

    public ApiClient asOperator() {
        Response token = new ApiClient(baseUrl).post("/api/v1/auth/operator-token",
                "{\"secret\":\"" + OPERATOR_SECRET + "\"}");
        if (token.status() != 200) {
            throw new IllegalStateException("Operator token request failed: " + token.body());
        }
        return withHeader("Authorization", "Bearer " + token.json().path("accessToken").asString());
    }

    public Response get(String path) {
        return send(request(path).GET());
    }

    public Response post(String path, String jsonBody) {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    public Response put(String path, String jsonBody) {
        return send(request(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    public Response delete(String path) {
        return send(request(path).DELETE());
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Accept", "application/json");
        defaultHeaders.forEach(builder::header);
        return builder;
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> headers.put(name.toLowerCase(), String.join(",", values)));
            return new Response(response.statusCode(), headers, response.body());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP call interrupted", e);
        }
    }
}
