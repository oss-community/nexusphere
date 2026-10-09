package com.nexusphere.ledger.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ledger.rate-limit")
public record RateLimitProperties(Integer perMinute, Integer burst) {

    public int limit() {
        return perMinute == null ? 0 : perMinute;
    }

    public int bucket() {
        return burst == null ? 0 : burst;
    }
}
