package com.nexusphere.ledger.mandate;

public enum MandateProblem {
    MALFORMED,
    WRONG_TYPE,
    UNTRUSTED_ISSUER,
    UNKNOWN_KEY,
    BAD_SIGNATURE,
    NOT_YET_VALID,
    EXPIRED,
    WRONG_AUDIENCE,
    NOT_COVERED,
    REVOKED,
    STATUS_UNAVAILABLE,
    KEY_BINDING_MISSING,
    KEY_BINDING_INVALID,
    KEY_NOT_BOUND
}
