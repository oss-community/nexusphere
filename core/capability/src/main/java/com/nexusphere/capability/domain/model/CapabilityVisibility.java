package com.nexusphere.capability.domain.model;

import com.nexusphere.shared.error.ValidationException;

import java.util.Arrays;

public enum CapabilityVisibility {
    PRIVATE,
    NETWORK,
    FEDERATED;

    public static CapabilityVisibility parse(String value) {
        return Arrays.stream(values()).filter(type -> type.name().equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("INVALID_VISIBILITY",
                        "The visibility must be one of " + Arrays.toString(values())));
    }
}
