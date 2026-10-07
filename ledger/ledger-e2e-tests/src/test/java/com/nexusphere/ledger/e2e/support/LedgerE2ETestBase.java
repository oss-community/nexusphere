package com.nexusphere.ledger.e2e.support;

import com.nexusphere.ledger.LedgerApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(classes = LedgerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"postgresql", "dev"})
@Import(LedgerE2ETestBase.Containers.class)
public abstract class LedgerE2ETestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        }
    }

    protected static final FakeMcpServer MCP = FakeMcpServer.start();
    protected static final int PORT = freePort();
    protected static final String ISSUER = "http://localhost:" + PORT;
    protected static final int SUPPLIER_PORT = freePort();
    protected static final String SUPPLIER_ISSUER = "http://localhost:" + SUPPLIER_PORT;

    @DynamicPropertySource
    static void mcpServers(DynamicPropertyRegistry registry) {
        registry.add("ledger.mcp.servers.files.url", MCP::url);
        registry.add("ledger.mcp.servers.files.authorization", () -> FakeMcpServer.AUTHORIZATION);
        registry.add("ledger.mcp.servers.offline.url", () -> "http://localhost:1/mcp");
        registry.add("server.port", () -> PORT);
        registry.add("ledger.mandate.issuer", () -> ISSUER);
        registry.add("ledger.a2a.peers.supplier.url", () -> SUPPLIER_ISSUER + "/a2a/in/sales");
        registry.add("ledger.a2a.peers.supplier.issuer", () -> SUPPLIER_ISSUER);
        registry.add("ledger.a2a.peers.offline.url", () -> "http://localhost:1/a2a/in/sales");
        registry.add("ledger.a2a.peers.offline.issuer", () -> "http://localhost:1");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @LocalServerPort
    private int port;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected LedgerClient ledger() {
        return new LedgerClient("http://localhost:" + port, LedgerClient.DEVELOPMENT_API_KEY);
    }

    protected LedgerClient anonymous() {
        return new LedgerClient("http://localhost:" + port, null);
    }

    protected record RegisteredAgent(String agentId, String apiKey, LedgerClient client) {
    }

    protected RegisteredAgent registerAgent(String owner) {
        String agentId = unique("agent");
        LedgerClient.Response response = ledger().post("/api/v1/agents",
                LedgerClient.json(Map.of("agentId", agentId, "name", "Agent " + agentId, "ownerId", owner)));
        if (response.status() != 201) {
            throw new IllegalStateException("Registering an agent failed: " + response.body());
        }
        String apiKey = response.json().path("apiKey").asString();
        return new RegisteredAgent(agentId, apiKey, ledger().withApiKey(apiKey));
    }

    protected static Map<String, Object> grantBody(String principalId, String agentId, List<String> actions,
                                                   List<String> targets) {
        Map<String, Object> body = new HashMap<>();
        body.put("principalId", principalId);
        body.put("agentId", agentId);
        body.put("actions", actions);
        body.put("targets", targets);
        body.put("expiresAt", Instant.now().plus(1, ChronoUnit.HOURS).toString());
        return body;
    }

    protected String grant(Map<String, Object> body) {
        LedgerClient.Response response = ledger().post("/api/v1/grants", LedgerClient.json(body));
        if (response.status() != 201) {
            throw new IllegalStateException("Creating a grant failed: " + response.body());
        }
        return response.json().path("id").asString();
    }

    protected static String decision(String principalId, String action, String target) {
        return LedgerClient.json(Map.of("principalId", principalId, "action", action, "target", target));
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    protected static Map<String, Object> toolCall(String agentId, String principalId, String tool) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("principalId", principalId);
        body.put("action", "tools/call");
        body.put("target", tool);
        body.put("decision", "ALLOW");
        body.put("outcome", "SUCCEEDED");
        body.put("attributes", Map.of("tool", tool, "server", "files"));
        return body;
    }
}
