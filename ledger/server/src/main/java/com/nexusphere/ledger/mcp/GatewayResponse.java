package com.nexusphere.ledger.mcp;

import java.util.UUID;

record GatewayResponse(int status, String sessionId, byte[] body, UUID decisionId) {
}
