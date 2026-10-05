package com.nexusphere.trust.domain.model;

import com.nexusphere.shared.error.ValidationException;

import java.util.Arrays;

public enum PartyType {
    NETWORK,
    ORGANIZATION,
    HUMAN,
    SERVICE,
    APPLICATION,
    AGENT,
    MACHINE;

    public boolean isIdentity() {
        return this != NETWORK && this != ORGANIZATION;
    }

    public static PartyType parse(String value) {
        return Arrays.stream(values()).filter(type -> type.name().equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("INVALID_PARTY_TYPE",
                        "The party type must be one of " + Arrays.toString(values())));
    }
}
