package com.nexusphere.ledger.mandate;

import java.net.URI;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HttpStatusListResolver implements StatusListResolver {

    private final HttpFetcher http;
    private final KeyResolver keys;
    private final Clock clock;
    private final Duration maxAge;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(StatusList list, Instant until) {
    }

    public HttpStatusListResolver(HttpFetcher http, KeyResolver keys, Clock clock, Duration maxAge) {
        this.http = http;
        this.keys = keys;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    @Override
    public StatusList resolve(String issuer, String uri) {
        Instant now = clock.instant();
        Cached cached = cache.get(uri);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.list();
        }
        StatusList list = verify(issuer, uri, http.get(URI.create(uri), "application/statuslist+jwt"), now);
        Instant until = now.plus(maxAge);
        cache.put(uri, new Cached(list, list.expiresAt().isBefore(until) ? list.expiresAt() : until));
        return list;
    }

    private StatusList verify(String issuer, String uri, String token, Instant now) {
        Jws.Parsed parsed = Jws.parse(token);
        if (!StatusList.TYPE.equals(parsed.header().path("typ").asString(null))
                || !Jws.ALGORITHM.equals(parsed.header().path("alg").asString(null))) {
            throw new IllegalStateException("The status list at " + uri + " is not an EdDSA statuslist+jwt");
        }
        PublicKey key = keys.resolve(issuer, parsed.header().path("kid").asString(""))
                .orElseThrow(() -> new IllegalStateException("The status list at " + uri
                        + " is signed with an unknown key"));
        if (!parsed.verify(key)) {
            throw new IllegalStateException("The status list at " + uri + " has a bad signature");
        }
        StatusList list = StatusList.fromPayload(parsed.payload());
        if (!issuer.equals(list.issuer()) || !uri.equals(list.uri())) {
            throw new IllegalStateException("The status list at " + uri + " belongs to another issuer or address");
        }
        if (!now.isBefore(list.expiresAt())) {
            throw new IllegalStateException("The status list at " + uri + " has expired");
        }
        return list;
    }
}
