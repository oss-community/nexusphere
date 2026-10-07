package com.nexusphere.ledger.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("ledger")
public record LedgerProperties(Security security, Signing signing, Checkpoint checkpoint, Mcp mcp,
                               Mandate mandate, A2a a2a) {

    public record Security(String apiKey) {
    }

    public record Signing(String privateKey, String publicKey) {
    }

    public record Checkpoint(Duration interval) {
    }

    public record Mandate(String issuer, Duration statusListTtl) {
    }

    public record A2a(Duration timeout, Duration requestMaxAge, Duration statusListCache, List<String> trustedIssuers,
                      Map<String, Peer> peers, Map<String, Agent> agents) {

        public record Peer(URI url, String issuer) {
        }

        public record Agent(URI url, String authorization) {
        }
    }

    public record Mcp(Duration timeout, Map<String, Server> servers) {

        public record Server(URI url, String authorization) {
        }
    }
}
