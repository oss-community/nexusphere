package com.nexusphere.capability.domain.model;

import com.nexusphere.shared.error.ValidationException;

import java.util.Arrays;

public enum OwnerType {
    ORGANIZATION,
    AGENT,
    MACHINE,
    SERVICE,
    APPLICATION;

    public boolean isIdentity() {
        return this != ORGANIZATION;
    }

    public static OwnerType parse(String value) {
        return Arrays.stream(values()).filter(type -> type.name().equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("INVALID_OWNER_TYPE",
                        "The owner type must be one of " + Arrays.toString(values())));
    }
}
