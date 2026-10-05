package com.nexusphere.shared.error;

/** The request conflicts with the current state, e.g. a stale version or a duplicate. */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(ErrorCategory.CONFLICT, code, message);
    }
}
