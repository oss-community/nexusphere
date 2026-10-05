package com.nexusphere.federation.domain.model;

import com.nexusphere.shared.error.ValidationException;

import java.util.Arrays;

public enum FederationScope {
    CAPABILITY_DISCOVERY,
    CAPABILITY_INVOCATION,
    IDENTITY_VISIBILITY,
    AGREEMENT_CREATION,
    TRANSACTION_EXCHANGE;

    public static FederationScope parse(String value) {
        return Arrays.stream(values()).filter(scope -> scope.name().equalsIgnoreCase(value == null ? "" : value.trim()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("INVALID_FEDERATION_SCOPE",
                        "The federation scope must be one of " + Arrays.toString(values())));
    }
}
