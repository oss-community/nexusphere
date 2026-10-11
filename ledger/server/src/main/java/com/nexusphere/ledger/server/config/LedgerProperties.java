package com.nexusphere.ledger.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("ledger")
public record LedgerProperties(Security security, Signing signing, Checkpoint checkpoint, Mcp mcp,
                               Mandate mandate, A2a a2a, Oidc oidc, Log log,
                               Compliance compliance, Timestamp timestamp, Rekor rekor, Policy policy,
                               Approval approval, Anomaly anomaly, Cluster cluster) {

    public boolean consentRequired() {
        return oidc != null && oidc.enabled();
    }

    public record Security(String apiKey) {
    }

    public record Policy(String defaultEffect) {
    }

    public record Cluster(String instanceId, Duration heartbeat) {
    }

    public record Anomaly(boolean enabled, Duration interval, Duration window, Duration baseline, Duration minHistory,
                          double threshold, long minEvents) {
    }

    public record Approval(Duration timeout, Duration pollInterval, URI webhookUrl, Ciba ciba) {

        public record Ciba(URI endpoint, URI tokenEndpoint, String clientId, String clientSecret, String scope) {

            public boolean enabled() {
                return endpoint != null && !endpoint.toString().isBlank();
            }
        }
    }

    public record Signing(String provider, String privateKey, String publicKey, String previousPrivateKey,
                          boolean unendorsedRotation, Vault vault) {

        public record Vault(String address, String token, String tokenFile, String namespace, String mount,
                            String key, Duration timeout) {
        }
    }

    public record Oidc(String issuer, String audience, URI jwksUri, String principalClaim, String clientId) {

        public boolean enabled() {
            return issuer != null && !issuer.isBlank();
        }

        public String publicClientId() {
            return clientId == null || clientId.isBlank() ? audience : clientId;
        }
    }

    public record Checkpoint(Duration interval) {
    }

    public record Compliance(List<String> profiles, String profileDirectory, String region,
                             Duration retentionInterval) {
    }

    public record Timestamp(String url, String certificates, Duration timeout) {

        public boolean enabled() {
            return url != null && !url.isBlank();
        }
    }

    public record Rekor(String url, String key, Duration timeout) {

        public boolean enabled() {
            return url != null && !url.isBlank();
        }
    }

    public record Log(String origin, Duration witnessTimeout, Map<String, Witness> witnesses,
                      Map<String, Watched> watched) {

        public record Witness(URI url, String key) {
        }

        public record Watched(String key) {
        }
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
