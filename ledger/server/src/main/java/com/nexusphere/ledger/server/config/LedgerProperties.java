package com.nexusphere.ledger.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

@ConfigurationProperties("ledger")
public record LedgerProperties(Security security, Signing signing, Checkpoint checkpoint, Mcp mcp,
                               Mandate mandate) {

    public record Security(String apiKey) {
    }

    public record Signing(String privateKey, String publicKey) {
    }

    public record Checkpoint(Duration interval) {
    }

    public record Mandate(String issuer, Duration statusListTtl) {
    }

    public record Mcp(Duration timeout, Map<String, Server> servers) {

        public record Server(URI url, String authorization) {
        }
    }
}
