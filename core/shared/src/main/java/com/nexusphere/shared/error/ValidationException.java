package com.nexusphere.shared.error;

public class ValidationException extends DomainException {

    public ValidationException(String code, String message) {
        super(ErrorCategory.VALIDATION_ERROR, code, message);
    }
}
