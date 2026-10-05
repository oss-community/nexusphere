package com.nexusphere.shared.error;

public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(ErrorCategory.CONFLICT, code, message);
    }
}
