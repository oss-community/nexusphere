package com.nexusphere.ledger.e2e.support;

import com.nexusphere.ledger.LedgerApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
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

    @LocalServerPort
    private int port;

    protected LedgerClient ledger() {
        return new LedgerClient("http://localhost:" + port, LedgerClient.DEVELOPMENT_API_KEY);
    }

    protected LedgerClient anonymous() {
        return new LedgerClient("http://localhost:" + port, null);
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
