package com.nexusphere.shared.error;

/** The resource does not exist, or exists outside what the caller may see; both look the same. */
public class NotFoundException extends DomainException {

    public NotFoundException(String resourceType, Object id) {
        super(ErrorCategory.NOT_FOUND, "NOT_FOUND", resourceType + " " + id + " was not found");
    }
}
