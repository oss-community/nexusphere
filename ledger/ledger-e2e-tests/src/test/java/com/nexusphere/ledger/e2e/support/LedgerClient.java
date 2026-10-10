package com.nexusphere.ledger.e2e.support;

import com.nexusphere.ledger.chain.EvidenceEntry;
import com.nexusphere.ledger.verifier.PackageVerifier;
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

public final class LedgerClient {

    public static final String DEVELOPMENT_API_KEY = "nexusphere-ledger-development-key-change-me";

    public record Response(int status, Map<String, String> headers, String body) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name.toLowerCase()));
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String apiKey;

    LedgerClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public LedgerClient withApiKey(String key) {
        return new LedgerClient(baseUrl, key);
    }

    public static String json(Map<String, ?> body) {
        return JSON.writeValueAsString(body);
    }

    public Response get(String path) {
        return send(request(path).GET());
    }

    public Response get(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = request(path).GET();
        headers.forEach(builder::header);
        return send(builder);
    }

    public Response post(String path, String jsonBody) {
        return send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    public Response post(String path, String jsonBody, Map<String, String> headers) {
        HttpRequest.Builder builder = request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        headers.forEach(builder::header);
        return send(builder);
    }

    public Response put(String path, String jsonBody) {
        return send(request(path).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    public Response postText(String path, String body) {
        return send(request(path).header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public Response delete(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = request(path).DELETE();
        headers.forEach(builder::header);
        return send(builder);
    }

    public Response post(String path) {
        return send(request(path).POST(HttpRequest.BodyPublishers.noBody()));
    }

    public JsonNode recordEvidence(Map<String, ?> body) {
        Response response = post("/api/v1/evidence", json(body));
        if (response.status() != 201) {
            throw new IllegalStateException("Recording evidence failed: " + response.status() + " " + response.body());
        }
        return response.json();
    }

    public static EvidenceEntry toEntry(JsonNode node) {
        return PackageVerifier.entry(node);
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    public byte[] getBytes(String path) {
        try {
            HttpResponse<byte[]> response = http.send(request(path).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("GET " + path + " answered " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
            return new Response(response.statusCode(), headers, response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
