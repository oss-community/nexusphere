package com.nexusphere.ledger.mcp;

import com.nexusphere.ledger.server.security.Caller;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    ResponseEntity<byte[]> post(Caller caller, @PathVariable String server,
                                @RequestHeader(name = PRINCIPAL_HEADER, required = false) String principalId,
                                @RequestHeader(name = McpUpstream.SESSION_HEADER, required = false) String sessionId,
                                @RequestHeader(name = McpUpstream.PROTOCOL_HEADER, required = false)
                                String protocolVersion,
                                @RequestBody byte[] body) {
        return respond(gateway.post(caller, server, principalId, sessionId, protocolVersion, body));
    }

    @DeleteMapping
    ResponseEntity<byte[]> delete(@PathVariable String server,
                                  @RequestHeader(name = McpUpstream.SESSION_HEADER, required = false) String sessionId,
                                  @RequestHeader(name = McpUpstream.PROTOCOL_HEADER, required = false)
                                  String protocolVersion) {
        return respond(gateway.delete(server, sessionId, protocolVersion));
    }

    private static ResponseEntity<byte[]> respond(GatewayResponse response) {
        HttpHeaders headers = new HttpHeaders();
        if (response.body().length > 0) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (response.sessionId() != null) {
            headers.set(McpUpstream.SESSION_HEADER, response.sessionId());
        }
        if (response.decisionId() != null) {
            headers.set(DECISION_HEADER, response.decisionId().toString());
        }
        return ResponseEntity.status(response.status()).headers(headers).body(response.body());
    }
}
