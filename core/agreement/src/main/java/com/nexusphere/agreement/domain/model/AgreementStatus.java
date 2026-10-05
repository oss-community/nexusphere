package com.nexusphere.agreement.domain.model;

public enum AgreementStatus {
    DRAFT,
    PROPOSED,
    ACCEPTED,
    ACTIVE,
    COMPLETED,
    REJECTED,
    TERMINATED;

    public boolean terminal() {
        return this == COMPLETED || this == REJECTED || this == TERMINATED;
    }
}
