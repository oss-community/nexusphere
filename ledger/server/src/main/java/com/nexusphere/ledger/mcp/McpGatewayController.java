package com.nexusphere.ledger.mcp;

import com.nexusphere.ledger.server.security.Caller;
import com.nexusphere.ledger.server.web.EventStream;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/mcp/{server}")
class McpGatewayController {

    static final String PRINCIPAL_HEADER = "X-Ledger-Principal";
    static final String DECISION_HEADER = "X-Ledger-Decision";

    private final McpGateway gateway;

    McpGatewayController(McpGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping
    ResponseEntity<StreamingResponseBody> post(
            Caller caller, @PathVariable String server,
            @RequestHeader(name = PRINCIPAL_HEADER, required = false) String principalId,
            @RequestHeader(name = McpUpstream.SESSION_HEADER, required = false) String sessionId,
            @RequestHeader(name = McpUpstream.PROTOCOL_HEADER, required = false) String protocolVersion,
            @RequestHeader(name = HttpHeaders.ACCEPT, required = false) String accept,
            @RequestBody byte[] body) {
        boolean acceptsStream = accept != null && accept.contains(EventStream.CONTENT_TYPE);
        return respond(gateway.post(caller, server, principalId, sessionId, protocolVersion, acceptsStream, body));
    }

    @GetMapping
    ResponseEntity<StreamingResponseBody> get(
            @PathVariable String server,
            @RequestHeader(name = McpUpstream.SESSION_HEADER, required = false) String sessionId,
            @RequestHeader(name = McpUpstream.PROTOCOL_HEADER, required = false) String protocolVersion,
            @RequestHeader(name = McpUpstream.LAST_EVENT_HEADER, required = false) String lastEventId) {
        return respond(gateway.get(server, sessionId, protocolVersion, lastEventId));
    }

    @DeleteMapping
    ResponseEntity<StreamingResponseBody> delete(
            @PathVariable String server,
            @RequestHeader(name = McpUpstream.SESSION_HEADER, required = false) String sessionId,
            @RequestHeader(name = McpUpstream.PROTOCOL_HEADER, required = false) String protocolVersion) {
        return respond(gateway.delete(server, sessionId, protocolVersion));
    }

    private static ResponseEntity<StreamingResponseBody> respond(GatewayResponse response) {
        HttpHeaders headers = new HttpHeaders();
        if (response.sessionId() != null) {
            headers.set(McpUpstream.SESSION_HEADER, response.sessionId());
        }
        if (response.decisionId() != null) {
            headers.set(DECISION_HEADER, response.decisionId().toString());
        }
        if (response.stream() != null) {
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            headers.setCacheControl(CacheControl.noCache());
            return ResponseEntity.status(response.status()).headers(headers).body(response.stream());
        }
        byte[] body = response.body();
        if (body.length > 0) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return ResponseEntity.status(response.status()).headers(headers).body(out -> out.write(body));
    }
}
