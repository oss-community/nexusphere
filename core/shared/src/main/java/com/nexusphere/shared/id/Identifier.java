package com.nexusphere.shared.id;

import com.nexusphere.shared.error.ValidationException;

import java.util.UUID;

public interface Identifier {

    UUID value();

    static UUID parse(String value, String type) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("INVALID_IDENTIFIER", type + " must not be blank");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_IDENTIFIER", type + " is not a valid UUID: " + value);
        }
    }
}
