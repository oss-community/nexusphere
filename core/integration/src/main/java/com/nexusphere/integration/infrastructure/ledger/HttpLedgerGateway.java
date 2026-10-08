package com.nexusphere.integration.infrastructure.ledger;

import com.nexusphere.integration.application.LedgerGateway;
import com.nexusphere.integration.application.LedgerProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class HttpLedgerGateway implements LedgerGateway {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY = 300;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final LedgerProperties properties;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    HttpLedgerGateway(LedgerProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    public void recordEvidence(Map<String, Object> evidence) {
        post("/api/v1/evidence", evidence);
    }

    @Override
    public void recordEvidence(List<Map<String, Object>> evidence) {
        post("/api/v1/evidence/batch", Map.of("items", evidence));
    }

    @Override
    public void ensureAgent(String agentId, String name, String ownerId) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("agentId", agentId);
        agent.put("name", name);
        agent.put("ownerId", ownerId);
        try {
            post("/api/v1/agents", agent);
        } catch (Unavailable e) {
            if (e.status() != 409) {
                throw e;
            }
        }
    }

    @Override
    public String createGrant(Map<String, Object> grant) {
        Object id = post("/api/v1/grants", grant).get("id");
        if (id == null) {
            throw new Unavailable("The ledger answered without a grant id", 502);
        }
        return id.toString();
    }

    @Override
    public void revokeGrant(String grantId, String reason) {
        try {
            post("/api/v1/grants/" + grantId + "/revoke", Map.of("reason", reason));
        } catch (Unavailable e) {
            if (e.status() != 404 && e.status() != 409) {
                throw e;
            }
        }
    }

    @Override
    public Map<String, Object> issueMandate(String grantId, String audience, String expiresAt) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("grantId", grantId);
        request.put("audience", audience);
        request.put("expiresAt", expiresAt);
        return post("/api/v1/mandates", request);
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + properties.apiKey());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new Unavailable("The ledger at " + properties.url() + " cannot be reached: " + e.getMessage(), 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("The call to the ledger was interrupted", 0);
        }
        if (response.statusCode() / 100 != 2) {
            throw new Unavailable("The ledger answered " + response.statusCode() + " to " + path + ": "
                    + abbreviate(response.body()), response.statusCode());
        }
        String text = response.body();
        return text == null || text.isBlank() ? Map.of() : json.readValue(text, MAP);
    }

    private URI uri(String path) {
        String base = properties.url().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY) + "...";
    }
}
