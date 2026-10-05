package com.nexusphere.federation.domain.model;

public enum FederationStatus {
    PROPOSED,
    PENDING_ACCEPTANCE,
    ACTIVE,
    SUSPENDED,
    REJECTED,
    TERMINATED;

    public boolean isTerminal() {
        return this == REJECTED || this == TERMINATED;
    }
}
