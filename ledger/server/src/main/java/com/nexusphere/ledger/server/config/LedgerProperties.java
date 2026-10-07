package com.nexusphere.ledger.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ledger")
public record LedgerProperties(Security security, Signing signing, Checkpoint checkpoint) {

    public record Security(String apiKey) {
    }

    public record Signing(String privateKey, String publicKey) {
    }

    public record Checkpoint(Duration interval) {
    }
}
