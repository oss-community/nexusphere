package com.nexusphere.shared.error;

import java.util.Map;
import java.util.Objects;

/**
 * Base of every expected failure. {@code code} is a stable machine-readable reason such as
 * {@code DELEGATION_SCOPE_VIOLATION}; {@code details} must never contain infrastructure internals.
 */
public class DomainException extends RuntimeException {

    private final ErrorCategory category;
    private final String code;
    private final Map<String, Object> details;

    public DomainException(ErrorCategory category, String code, String message) {
        this(category, code, message, Map.of());
    }

    public DomainException(ErrorCategory category, String code, String message, Map<String, Object> details) {
        super(message);
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.details = Map.copyOf(details);
    }

    public ErrorCategory category() {
        return category;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
