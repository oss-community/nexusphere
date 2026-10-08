package com.nexusphere.integration.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("nexusphere.ledger")
public record LedgerProperties(URI url, String apiKey, Duration forwardInterval, Integer batchSize) {

    public boolean enabled() {
        return url != null && !url.toString().isBlank();
    }

    public int batch() {
        return batchSize == null || batchSize < 1 ? 100 : Math.min(batchSize, 500);
    }
}
