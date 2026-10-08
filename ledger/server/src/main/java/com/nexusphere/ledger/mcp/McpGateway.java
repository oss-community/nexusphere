package com.nexusphere.ledger.mcp;

import com.nexusphere.ledger.authorization.application.DecisionService;
import com.nexusphere.ledger.authorization.domain.model.DecisionRequest;
import com.nexusphere.ledger.authorization.domain.model.DecisionResult;
import com.nexusphere.ledger.authorization.domain.model.OutcomeReport;
import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.evidence.domain.model.Decision;
import com.nexusphere.ledger.evidence.domain.model.Outcome;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.EventStream;
import com.nexusphere.ledger.server.web.LedgerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
class McpGateway {

    static final String TOOLS_CALL = "tools/call";
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int INVALID_PARAMS = -32602;
    static final int UPSTREAM_UNAVAILABLE = -32002;
    static final int DENIED = -32003;
    static final Set<Integer> REJECTED_CODES = Set.of(PARSE_ERROR, INVALID_REQUEST, -32601, INVALID_PARAMS);

    private static final Logger log = LoggerFactory.getLogger(McpGateway.class);

    private final LedgerProperties properties;
    private final McpUpstream upstream;
    private final DecisionService decisions;
    private final JsonMapper json;

    McpGateway(LedgerProperties properties, McpUpstream upstream, DecisionService decisions, JsonMapper json) {
        this.properties = properties;
        this.upstream = upstream;
        this.decisions = decisions;
        this.json = json;
    }

    GatewayResponse post(Caller caller, String serverName, String principalId, String sessionId,
                         String protocolVersion, boolean acceptsStream, byte[] body) {
        LedgerProperties.Mcp.Server server = server(serverName);
        JsonNode message;
        try {
            message = json.readTree(body);
        } catch (JacksonException e) {
            return error(400, null, PARSE_ERROR, "The request is not valid JSON.", null);
        }
        if (message == null || !message.isObject()) {
            return error(400, null, INVALID_REQUEST, "The gateway accepts one JSON-RPC message per request.", null);
        }
        JsonNode id = message.get("id");
        if (!TOOLS_CALL.equals(message.path("method").asString(null)) || id == null) {
            return forward(server, sessionId, protocolVersion, body, id, acceptsStream);
        }
        if (caller.isOperator()) {
            return error(403, id, DENIED, "Only agents may call tools through the gateway.", null);
        }
        String tool = message.path("params").path("name").asString(null);
        if (principalId == null || principalId.isBlank() || tool == null || tool.isBlank()) {
            return error(400, id, INVALID_PARAMS,
                    "A tools/call needs params.name and the " + McpGatewayController.PRINCIPAL_HEADER + " header.",
                    null);
        }
        DecisionResult decision;
        try {
            decision = decisions.decide(new DecisionRequest(caller.agentId(), principalId, TOOLS_CALL,
                    serverName + "/" + tool, Hashes.sha256(body), correlation(sessionId),
                    Map.of("mcp.server", serverName, "mcp.tool", truncate(tool, 1024))));
        } catch (LedgerException e) {
            return error(400, id, INVALID_PARAMS, e.getMessage(), e.details());
        }
        UUID decisionId = decision.decision().id();
        if (decision.decision().decision() == Decision.DENY) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("decisionId", decisionId.toString());
            data.put("reasonCode", decision.decision().reasonCode().name());
            GatewayResponse denied = error(200, id, DENIED, "Denied by Nexusphere Ledger: " + decision.reason(),
                    data);
            return new GatewayResponse(denied.status(), sessionId, denied.body(), decisionId);
        }
        UpstreamResponse answer;
        try {
            answer = upstream.post(server, timeout(), sessionId, protocolVersion, body);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("MCP server {} is unavailable: {}", server.url(), e.getMessage());
            GatewayResponse failed = error(502, id, UPSTREAM_UNAVAILABLE, "The MCP server is unavailable.", null);
            record(decisionId, caller, failed.status(), failed.body(), Map.of(), false);
            return new GatewayResponse(failed.status(), sessionId, failed.body(), decisionId);
        }
        String session = answer.sessionId() == null ? sessionId : answer.sessionId();
        if (!answer.streamed()) {
            record(decisionId, caller, answer.status(), answer.body(), Map.of(), true);
            return new GatewayResponse(answer.status(), session, answer.body(), decisionId);
        }
        if (!acceptsStream) {
            byte[] result;
            try {
                result = upstream.answer(answer.stream(), id);
            } catch (IOException e) {
                result = null;
            }
            record(decisionId, caller, answer.status(), result, Map.of(), true);
            return new GatewayResponse(answer.status(), session, result == null ? new byte[0] : result, decisionId);
        }
        return new GatewayResponse(answer.status(), session, null, decisionId,
                out -> relayAnswer(answer, id, decisionId, caller, out));
    }

    GatewayResponse get(String serverName, String sessionId, String protocolVersion, String lastEventId) {
        LedgerProperties.Mcp.Server server = server(serverName);
        try {
            UpstreamResponse response = upstream.get(server, timeout(), sessionId, protocolVersion, lastEventId);
            String session = response.sessionId() == null ? sessionId : response.sessionId();
            if (!response.streamed()) {
                return new GatewayResponse(response.status(), session, response.body(), null);
            }
            return new GatewayResponse(response.status(), session, null, null, out -> {
                try (InputStream stream = response.stream()) {
                    EventStream.relay(stream, out, event -> true);
                }
            });
        } catch (IOException e) {
            return error(502, null, UPSTREAM_UNAVAILABLE, "The MCP server " + serverName + " is unavailable.", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(502, null, UPSTREAM_UNAVAILABLE, "The MCP server " + serverName + " is unavailable.", null);
        }
    }

    GatewayResponse delete(String serverName, String sessionId, String protocolVersion) {
        LedgerProperties.Mcp.Server server = server(serverName);
        try {
            UpstreamResponse response = upstream.delete(server, timeout(), sessionId, protocolVersion);
            return new GatewayResponse(response.status(), null, response.body(), null);
        } catch (IOException e) {
            return error(502, null, UPSTREAM_UNAVAILABLE, "The MCP server " + serverName + " is unavailable.", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(502, null, UPSTREAM_UNAVAILABLE, "The MCP server " + serverName + " is unavailable.", null);
        }
    }

    private void relayAnswer(UpstreamResponse answer, JsonNode id, UUID decisionId, Caller caller,
                             OutputStream out) throws IOException {
        byte[][] result = new byte[1][];
        int[] events = {0};
        try (InputStream stream = answer.stream()) {
            EventStream.relay(stream, out, event -> {
                events[0]++;
                if (result[0] == null && event.data() != null) {
                    byte[] data = event.data().getBytes(StandardCharsets.UTF_8);
                    if (upstream.isAnswer(data, id)) {
                        result[0] = data;
                    }
                }
                return true;
            });
        } catch (IOException e) {
            record(decisionId, caller, answer.status(), result[0], Map.of("mcp.events", String.valueOf(events[0]),
                    "mcp.stream", "BROKEN"), true);
            throw e;
        }
        record(decisionId, caller, answer.status(), result[0], Map.of("mcp.events", String.valueOf(events[0]),
                "mcp.stream", "COMPLETE"), true);
    }

    private GatewayResponse forward(LedgerProperties.Mcp.Server server, String sessionId, String protocolVersion,
                                    byte[] body, JsonNode id, boolean acceptsStream) {
        try {
            UpstreamResponse response = upstream.post(server, timeout(), sessionId, protocolVersion, body);
            String session = response.sessionId() == null ? sessionId : response.sessionId();
            if (!response.streamed()) {
                return new GatewayResponse(response.status(), session, response.body(), null);
            }
            if (!acceptsStream) {
                return new GatewayResponse(response.status(), session, upstream.answer(response.stream(), id), null);
            }
            return new GatewayResponse(response.status(), session, null, null, out -> {
                try (InputStream stream = response.stream()) {
                    EventStream.relay(stream, out, event -> true);
                }
            });
        } catch (IOException e) {
            log.warn("MCP server {} is unavailable: {}", server.url(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        GatewayResponse failed = error(502, id, UPSTREAM_UNAVAILABLE, "The MCP server is unavailable.", null);
        return new GatewayResponse(failed.status(), sessionId, failed.body(), null);
    }

    private void record(UUID decisionId, Caller caller, int status, byte[] body, Map<String, String> extra,
                        boolean reached) {
        Outcome outcome = outcome(status, body);
        Map<String, String> attributes = new LinkedHashMap<>(extra);
        attributes.put("mcp.status", String.valueOf(status));
        OutcomeReport report = new OutcomeReport(outcome, Hashes.sha256(body == null ? new byte[0] : body),
                outcome == Outcome.FAILED ? failure(status, body) : null, attributes);
        if (!reached || rejected(status, body)) {
            decisions.reportUndelivered(decisionId, caller.agentId(), report);
        } else {
            decisions.reportOutcome(decisionId, caller.agentId(), report);
        }
    }

    private boolean rejected(int status, byte[] body) {
        if (status >= 400 && status < 500) {
            return true;
        }
        if (body == null) {
            return false;
        }
        try {
            JsonNode message = json.readTree(body);
            return message != null && REJECTED_CODES.contains(message.path("error").path("code").asInt(0));
        } catch (JacksonException e) {
            return false;
        }
    }

    private Outcome outcome(int status, byte[] body) {
        if (status < 200 || status >= 300 || body == null) {
            return Outcome.FAILED;
        }
        try {
            JsonNode message = json.readTree(body);
            if (message == null || message.has("error") || message.path("result").path("isError").asBoolean(false)) {
                return Outcome.FAILED;
            }
            return Outcome.SUCCEEDED;
        } catch (JacksonException e) {
            return Outcome.FAILED;
        }
    }

    private String failure(int status, byte[] body) {
        if (status < 200 || status >= 300) {
            return "The MCP server answered with HTTP " + status + ".";
        }
        if (body == null) {
            return "The stream ended without an answer to the tool call.";
        }
        try {
            JsonNode message = json.readTree(body);
            if (message != null && message.has("error")) {
                return truncate("JSON-RPC error " + message.path("error").path("code").asInt() + ": "
                        + message.path("error").path("message").asString(""), 500);
            }
            return "The tool reported an error.";
        } catch (JacksonException e) {
            return "The MCP server answered with a body that is not JSON.";
        }
    }

    private GatewayResponse error(int status, JsonNode id, int code, String message, Map<String, Object> data) {
        ObjectNode response = json.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id == null ? json.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        if (data != null && !data.isEmpty()) {
            error.set("data", json.valueToTree(data));
        }
        return new GatewayResponse(status, null, json.writeValueAsBytes(response), null);
    }

    private LedgerProperties.Mcp.Server server(String name) {
        LedgerProperties.Mcp mcp = properties.mcp();
        LedgerProperties.Mcp.Server server = mcp == null || mcp.servers() == null ? null : mcp.servers().get(name);
        if (server == null || server.url() == null) {
            throw LedgerException.notFound("MCP server " + name);
        }
        return server;
    }

    private Duration timeout() {
        LedgerProperties.Mcp mcp = properties.mcp();
        return mcp == null || mcp.timeout() == null ? Duration.ofSeconds(60) : mcp.timeout();
    }

    private static String correlation(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? null : truncate(sessionId, 128);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
