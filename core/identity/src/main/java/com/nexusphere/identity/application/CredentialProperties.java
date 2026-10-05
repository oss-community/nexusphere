package com.nexusphere.identity.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("nexusphere.identity.credential")
public record CredentialProperties(Duration ttl, Duration maxTtl) {

    public CredentialProperties {
        ttl = ttl == null ? Duration.ofDays(90) : ttl;
        maxTtl = maxTtl == null ? Duration.ofDays(365) : maxTtl;
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(maxTtl) > 0) {
            throw new IllegalStateException(
                    "nexusphere.identity.credential.ttl must be positive and not exceed max-ttl");
        }
    }
}
