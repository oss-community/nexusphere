package com.nexusphere.ledger.a2a;

import java.time.Instant;
import java.util.UUID;

record A2aExchange(
        UUID id,
        String direction,
        String peer,
        UUID requestId,
        String agentId,
        String principalId,
        String method,
        UUID mandateId,
        String mandateToken,
        String requestToken,
        String requestHash,
        String responseHash,
        Integer status,
        String outcome,
        Long evidenceSequence,
        String receipt,
        String receiptStatus,
        Instant createdAt) {

    static final String OUTBOUND = "OUTBOUND";
    static final String INBOUND = "INBOUND";
}
