package com.nexusphere.shared.error;

/** Input that is malformed regardless of state. */
public class ValidationException extends DomainException {

    public ValidationException(String code, String message) {
        super(ErrorCategory.VALIDATION_ERROR, code, message);
    }
}
