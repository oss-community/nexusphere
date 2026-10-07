package com.nexusphere.ledger.demo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DemoMcpServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private DemoMcpServer server;

    @BeforeEach
    void start() throws Exception {
        server = new DemoMcpServer("localhost", 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void initializeListsAndCallsTools() throws Exception {
        HttpResponse<String> init = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        JsonNode list = JSON.readTree(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").body());
        JsonNode read = JSON.readTree(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"/reports/q3.txt\"}}}").body());
        JsonNode missing = JSON.readTree(post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"/nope\"}}}").body());
        HttpResponse<String> notification = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        assertThat(init.headers().firstValue("Mcp-Session-Id")).isPresent();
        assertThat(JSON.readTree(init.body()).path("result").path("protocolVersion").asString())
                .isEqualTo(DemoMcpServer.PROTOCOL_VERSION);
        assertThat(list.path("result").path("tools").size()).isEqualTo(3);
        assertThat(read.path("result").path("content").get(0).path("text").asString()).contains("Q3");
        assertThat(read.path("result").path("isError").asBoolean()).isFalse();
        assertThat(missing.path("result").path("isError").asBoolean()).isTrue();
        assertThat(notification.statusCode()).isEqualTo(202);
    }

    @Test
    void unknownToolsAndMethodsAreJsonRpcErrors() throws Exception {
        JsonNode tool = JSON.readTree(post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"rm_rf\"}}").body());
        JsonNode method = JSON.readTree(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/list\"}").body());

        assertThat(tool.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(method.path("error").path("code").asInt()).isEqualTo(-32601);
    }
}
