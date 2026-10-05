package com.nexusphere.bootstrap.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@ConfigurationProperties("nexusphere.security.operator")
public record OperatorProperties(String secret) {

    public OperatorProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("nexusphere.security.operator.secret must have at least 32 bytes");
        }
    }

    boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }
}
