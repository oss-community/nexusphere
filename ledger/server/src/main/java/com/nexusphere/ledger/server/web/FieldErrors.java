package com.nexusphere.ledger.server.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class FieldErrors {

    private final Map<String, Object> errors = new LinkedHashMap<>();

    public FieldErrors text(String field, String value, boolean required, int maxLength) {
        if (value == null) {
            if (required) {
                errors.put(field, "is required");
            }
        } else if (value.isBlank()) {
            errors.put(field, "must not be blank");
        } else if (value.length() > maxLength) {
            errors.put(field, "must be at most " + maxLength + " characters");
        } else if (value.chars().anyMatch(Character::isISOControl)) {
            errors.put(field, "must not contain control characters");
        }
        return this;
    }

    public FieldErrors pattern(String field, String value, Pattern pattern) {
        if (value != null && !errors.containsKey(field) && !pattern.matcher(value).matches()) {
            errors.put(field, "must match " + pattern.pattern());
        }
        return this;
    }

    public FieldErrors reject(String field, String message) {
        errors.putIfAbsent(field, message);
        return this;
    }

    public void throwIfAny(String message) {
        if (!errors.isEmpty()) {
            throw LedgerException.invalid(message, errors);
        }
    }
}
