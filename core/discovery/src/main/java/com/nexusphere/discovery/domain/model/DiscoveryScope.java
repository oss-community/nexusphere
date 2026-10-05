package com.nexusphere.discovery.domain.model;

import com.nexusphere.shared.error.ValidationException;

import java.util.Arrays;

public enum DiscoveryScope {
    LOCAL,
    FEDERATED,
    ALL;

    public static DiscoveryScope parse(String value) {
        if (value == null) {
            return ALL;
        }
        return Arrays.stream(values()).filter(scope -> scope.name().equalsIgnoreCase(value.trim())).findFirst()
                .orElseThrow(() -> new ValidationException("INVALID_DISCOVERY_SCOPE",
                        "The discovery scope must be one of " + Arrays.toString(values())));
    }

    public boolean includesLocal() {
        return this != FEDERATED;
    }

    public boolean includesFederated() {
        return this != LOCAL;
    }
}
