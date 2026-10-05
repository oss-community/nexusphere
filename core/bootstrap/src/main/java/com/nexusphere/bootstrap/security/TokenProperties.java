package com.nexusphere.bootstrap.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties("nexusphere.security.token")
public record TokenProperties(String issuer, String secret, Duration ttl) {

    public TokenProperties {
        Objects.requireNonNull(issuer, "nexusphere.security.token.issuer must be set");
        Objects.requireNonNull(ttl, "nexusphere.security.token.ttl must be set");
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("nexusphere.security.token.secret must have at least 32 bytes; set APP_TOKEN_SECRET");
        }
    }
}
