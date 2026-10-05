package com.nexusphere.trust.domain.model;

import com.nexusphere.shared.error.ValidationException;

import java.util.Arrays;

public enum TrustLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static TrustLevel parse(String value) {
        if (value == null) {
            return MEDIUM;
        }
        return Arrays.stream(values()).filter(level -> level.name().equalsIgnoreCase(value.trim())).findFirst()
                .orElseThrow(() -> new ValidationException("INVALID_TRUST_LEVEL",
                        "The trust level must be one of " + Arrays.toString(values())));
    }
}
