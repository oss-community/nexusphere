package com.nexusphere.shared.error;

public class NotFoundException extends DomainException {

    public NotFoundException(String resourceType, Object id) {
        super(ErrorCategory.NOT_FOUND, "NOT_FOUND", resourceType + " " + id + " was not found");
    }
}
