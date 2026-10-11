package com.nexusphere.ledger.authorization.domain.model;

public enum ReasonCode {
    ALLOWED_BY_GRANT,
    AGENT_NOT_ACTIVE,
    NO_ACTIVE_GRANT,
    NOT_COVERED,
    NOT_YET_VALID,
    USES_EXHAUSTED,
    POLICY_DENIED,
    ALLOWED_BY_APPROVAL,
    APPROVAL_PENDING,
    APPROVAL_DENIED,
    APPROVAL_EXPIRED
}
