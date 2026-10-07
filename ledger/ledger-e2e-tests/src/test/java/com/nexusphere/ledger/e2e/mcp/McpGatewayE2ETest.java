package com.nexusphere.ledger.e2e.mcp;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.e2e.support.FakeMcpServer;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.LedgerE2ETestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpGatewayE2ETest extends LedgerE2ETestBase {

    private static final String FILES = "/mcp/files";

    private static String toolCall(int id, String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{\"path\":\"/tmp/report.txt\"}}}";
    }

    private static Map<String, String> as(String principal) {
        return Map.of("X-Ledger-Principal", principal, "Mcp-Session-Id", FakeMcpServer.SESSION_ID);
    }

    @Test
    void anAllowedToolCallIsForwardedAndRecordedWithItsOutcome() {
        RegisteredAgent agent = registerAgent("acme");
        String grantId = grant(grantBody("alice", agent.agentId(), List.of("tools/call"), List.of("files/*")));
        String request = toolCall(7, "read_file");

        LedgerClient.Response response = agent.client().post(FILES, request, as("alice"));
        String decisionId = response.header("X-Ledger-Decision").orElseThrow();
        JsonNode decision = ledger().get("/api/v1/evidence/" + decisionId).json();
        JsonNode outcome = ledger().get("/api/v1/evidence/" + ledger().get("/api/v1/decisions/" + decisionId).json()
                .path("outcomeEvidenceId").asString()).json();

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("id").asInt()).isEqualTo(7);
        assertThat(response.json().path("result").path("content").get(0).path("text").asString())
                .contains("/tmp/report.txt");
        assertThat(MCP.calls("read_file")).isEqualTo(1);
        assertThat(MCP.lastAuthorization()).isEqualTo(FakeMcpServer.AUTHORIZATION);
        assertThat(MCP.lastSessionId()).isEqualTo(FakeMcpServer.SESSION_ID);
        assertThat(decision.path("decision").asString()).isEqualTo("ALLOW");
        assertThat(decision.path("target").asString()).isEqualTo("files/read_file");
        assertThat(decision.path("delegationId").asString()).isEqualTo(grantId);
        assertThat(decision.path("correlationId").asString()).isEqualTo(FakeMcpServer.SESSION_ID);
        assertThat(decision.path("inputHash").asString())
                .isEqualTo(Hashes.sha256(request.getBytes(StandardCharsets.UTF_8)));
        assertThat(decision.path("attributes").path("mcp.tool").asString()).isEqualTo("read_file");
        assertThat(outcome.path("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(outcome.path("outputHash").asString())
                .isEqualTo(Hashes.sha256(response.body().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aDeniedToolCallNeverReachesTheServer() {
        RegisteredAgent agent = registerAgent("acme");
        grant(grantBody("bob", agent.agentId(), List.of("tools/call"), List.of("files/read_*")));

        LedgerClient.Response response = agent.client().post(FILES, toolCall(8, "delete_everything"), as("bob"));
        JsonNode error = response.json().path("error");
        JsonNode evidence = ledger().get("/api/v1/evidence/" + error.path("data").path("decisionId").asString())
                .json();

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("id").asInt()).isEqualTo(8);
        assertThat(error.path("code").asInt()).isEqualTo(-32003);
        assertThat(error.path("data").path("reasonCode").asString()).isEqualTo("NOT_COVERED");
        assertThat(MCP.calls("delete_everything")).isZero();
        assertThat(evidence.path("decision").asString()).isEqualTo("DENY");
        assertThat(evidence.path("outcome").asString()).isEqualTo("DENIED");
    }

    @Test
    void toolErrorsAreRecordedAsFailedOutcomes() {
        RegisteredAgent agent = registerAgent("acme");
        grant(grantBody("carol", agent.agentId(), List.of("tools/call"), List.of("files/*")));

        String failed = agent.client().post(FILES, toolCall(9, "fail"), as("carol")).header("X-Ledger-Decision")
                .orElseThrow();
        String crashed = agent.client().post(FILES, toolCall(10, "crash"), as("carol")).header("X-Ledger-Decision")
                .orElseThrow();

        assertThat(ledger().get("/api/v1/decisions/" + failed).json().path("outcome").asString())
                .isEqualTo("FAILED");
        JsonNode crash = ledger().get("/api/v1/decisions/" + crashed).json();
        assertThat(crash.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(ledger().get("/api/v1/evidence/" + crash.path("outcomeEvidenceId").asString()).json()
                .path("reason").asString()).contains("Tool crashed");
    }

    @Test
    void streamedResponsesAreReturnedAsJson() {
        RegisteredAgent agent = registerAgent("acme");
        grant(grantBody("dave", agent.agentId(), List.of("tools/call"), List.of("files/*")));

        LedgerClient.Response response = agent.client().post(FILES, toolCall(11, "search_sse"), as("dave"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(response.json().path("id").asInt()).isEqualTo(11);
        assertThat(response.json().has("result")).isTrue();
        assertThat(ledger().get("/api/v1/decisions/" + response.header("X-Ledger-Decision").orElseThrow()).json()
                .path("outcome").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void otherMessagesPassThroughWithTheSession() {
        RegisteredAgent agent = registerAgent("acme");
        long before = ledger().get("/api/v1/ledger/head").json().path("sequence").asLong();

        LedgerClient.Response initialize = agent.client().post(FILES,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", Map.of());
        LedgerClient.Response notification = agent.client().post(FILES,
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of("Mcp-Session-Id",
                        FakeMcpServer.SESSION_ID));
        LedgerClient.Response list = agent.client().post(FILES,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", Map.of("Mcp-Session-Id",
                        FakeMcpServer.SESSION_ID));
        LedgerClient.Response closed = agent.client().delete(FILES, Map.of("Mcp-Session-Id",
                FakeMcpServer.SESSION_ID));

        assertThat(initialize.header("Mcp-Session-Id")).hasValue(FakeMcpServer.SESSION_ID);
        assertThat(initialize.json().path("result").path("serverInfo").path("name").asString()).isEqualTo("fake");
        assertThat(notification.status()).isEqualTo(202);
        assertThat(list.json().path("result").path("tools").get(0).path("name").asString()).isEqualTo("echo");
        assertThat(closed.status()).isEqualTo(200);
        assertThat(ledger().get("/api/v1/ledger/head").json().path("sequence").asLong()).isEqualTo(before);
    }

    @Test
    void badRequestsAreAnsweredAsJsonRpcErrors() {
        RegisteredAgent agent = registerAgent("acme");

        LedgerClient.Response anonymous = anonymous().post(FILES, toolCall(1, "echo"), as("erin"));
        LedgerClient.Response operator = ledger().post(FILES, toolCall(2, "echo"), as("erin"));
        LedgerClient.Response noPrincipal = agent.client().post(FILES, toolCall(3, "echo"), Map.of());
        LedgerClient.Response notJson = agent.client().post(FILES, "{oops", Map.of());
        LedgerClient.Response batch = agent.client().post(FILES, "[" + toolCall(4, "echo") + "]", as("erin"));
        LedgerClient.Response unknownServer = agent.client().post("/mcp/nowhere", toolCall(5, "echo"), as("erin"));

        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(operator.status()).isEqualTo(403);
        assertThat(noPrincipal.status()).isEqualTo(400);
        assertThat(noPrincipal.json().path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(notJson.json().path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(batch.json().path("error").path("code").asInt()).isEqualTo(-32600);
        assertThat(unknownServer.status()).isEqualTo(404);
        assertThat(MCP.calls("echo")).isZero();
    }

    @Test
    void anUnreachableServerIsRecordedAsAFailedCall() {
        RegisteredAgent agent = registerAgent("acme");
        grant(grantBody("frank", agent.agentId(), List.of("tools/call"), List.of("offline/*")));

        LedgerClient.Response response = agent.client().post("/mcp/offline", toolCall(12, "ping"), as("frank"));

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.json().path("error").path("code").asInt()).isEqualTo(-32002);
        assertThat(ledger().get("/api/v1/decisions/" + response.header("X-Ledger-Decision").orElseThrow()).json()
                .path("outcome").asString()).isEqualTo("FAILED");
        assertThat(ledger().get("/api/v1/verification").json().path("valid").asBoolean()).isTrue();
    }
}
