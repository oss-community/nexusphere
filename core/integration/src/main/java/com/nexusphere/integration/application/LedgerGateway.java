package com.nexusphere.integration.application;

import java.util.Map;

public interface LedgerGateway {

    void recordEvidence(Map<String, Object> evidence);

    void ensureAgent(String agentId, String name, String ownerId);

    String createGrant(Map<String, Object> grant);

    void revokeGrant(String grantId, String reason);

    Map<String, Object> issueMandate(String grantId, String audience, String expiresAt);

    final class Unavailable extends RuntimeException {

        private final int status;

        public Unavailable(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
