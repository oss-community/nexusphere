package com.nexusphere.ledger.mcp;

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

record GatewayResponse(int status, String sessionId, byte[] body, UUID decisionId, StreamingResponseBody stream) {

    GatewayResponse(int status, String sessionId, byte[] body, UUID decisionId) {
        this(status, sessionId, body, decisionId, null);
    }
}
