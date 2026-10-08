package com.nexusphere.ledger.authorization.domain.model;

public enum GrantStatus {
    PENDING,
    ACTIVE,
    NOT_YET_VALID,
    EXHAUSTED,
    EXPIRED,
    DENIED,
    REVOKED
}
