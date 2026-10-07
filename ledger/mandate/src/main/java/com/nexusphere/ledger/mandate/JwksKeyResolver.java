package com.nexusphere.ledger.mandate;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class JwksKeyResolver implements KeyResolver {

    public static final String KEYS_PATH = "/public/v1/keys";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(30);

    private record Cached(Map<String, PublicKey> keys, Instant fetchedAt) {
    }

    private final HttpFetcher http;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public JwksKeyResolver(HttpFetcher http, Clock clock, Duration ttl) {
        this.http = http;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public Optional<PublicKey> resolve(String issuer, String keyId) {
        Instant now = clock.instant();
        Cached cached = cache.get(issuer);
        boolean stale = cached == null || now.isAfter(cached.fetchedAt().plus(ttl));
        boolean unknown = cached != null && !cached.keys().containsKey(keyId)
                && now.isAfter(cached.fetchedAt().plus(MIN_REFRESH));
        if (stale || unknown) {
            cached = new Cached(fetch(issuer), now);
            cache.put(issuer, cached);
        }
        return Optional.ofNullable(cached.keys().get(keyId));
    }

    private Map<String, PublicKey> fetch(String issuer) {
        JsonNode jwks = Jws.JSON.readTree(http.get(URI.create(issuer + KEYS_PATH), "application/jwk-set+json"));
        Map<String, PublicKey> keys = new HashMap<>();
        for (JsonNode jwk : jwks.path("keys")) {
            String kid = jwk.path("kid").asString(null);
            if (kid == null || !"OKP".equals(jwk.path("kty").asString(null))) {
                continue;
            }
            try {
                keys.put(kid, Jwk.publicKey(jwk));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return keys;
    }
}
