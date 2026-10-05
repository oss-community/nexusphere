package com.nexusphere.shared.context;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

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
