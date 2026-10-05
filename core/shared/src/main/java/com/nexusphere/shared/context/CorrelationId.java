package com.nexusphere.shared.context;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Connects a request with its authorization decisions, domain events, transactions and audit events. */
public record CorrelationId(String value) {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public CorrelationId {
        Objects.requireNonNull(value, "correlation id must not be null");
        if (!ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException("correlation id has an invalid format");
        }
    }

    public static CorrelationId newId() {
        return new CorrelationId(UUID.randomUUID().toString());
    }

    /** Accepts a client-supplied value when it is well formed, otherwise generates a new one. */
    public static CorrelationId fromNullable(String candidate) {
        if (candidate != null && ALLOWED.matcher(candidate).matches()) {
            return new CorrelationId(candidate);
        }
        return newId();
    }

    @Override
    public String toString() {
        return value;
    }
}
