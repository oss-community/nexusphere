package com.nexusphere.ledger.mandate;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class MandateVerifier {

    private final Set<String> issuers;
    private final KeyResolver keys;
    private final StatusListResolver statusLists;
    private final String audience;
    private final Clock clock;
    private final Duration clockSkew;
    private final Duration keyBindingMaxAge;
    private final boolean requireKeyBinding;

    private MandateVerifier(Builder builder) {
        this.issuers = Set.copyOf(builder.issuers);
        this.clock = builder.clock;
        this.clockSkew = builder.clockSkew;
        this.keyBindingMaxAge = builder.keyBindingMaxAge;
        this.requireKeyBinding = builder.requireKeyBinding;
        this.audience = builder.audience;
        HttpFetcher http = builder.http == null ? HttpFetcher.create(builder.timeout) : builder.http;
        this.keys = builder.keys == null ? new JwksKeyResolver(http, clock, builder.cacheTtl) : builder.keys;
        this.statusLists = builder.skipStatus ? null
                : builder.statusLists == null ? new HttpStatusListResolver(http, keys, clock, builder.cacheTtl)
                : builder.statusLists;
    }

    public static Builder builder() {
        return new Builder();
    }

    public MandateCheck verify(String token) {
        return check(token, null, null, false, null);
    }

    public MandateCheck verify(String token, String action, String target) {
        return check(token, action, target, true, null);
    }

    public MandateCheck verifyBound(String token, String nonce) {
        return check(token, null, null, false, nonce);
    }

    public MandateCheck verifyBound(String token, String nonce, String action, String target) {
        return check(token, action, target, true, nonce);
    }

    private MandateCheck check(String token, String action, String target, boolean coverage, String nonce) {
        List<MandateCheck.Problem> problems = new ArrayList<>();
        Jws.Parsed parsed;
        MandateClaims claims;
        SdJwt sdJwt;
        try {
            sdJwt = SdJwt.parse(token);
            parsed = sdJwt.jwt();
            claims = MandateClaims.fromPayload(sdJwt.claims());
        } catch (IllegalArgumentException e) {
            return failed(null, MandateProblem.MALFORMED, e.getMessage());
        }
        if (!MandateClaims.TYPE.equals(parsed.header().path("typ").asString(null))
                || !Jws.ALGORITHM.equals(parsed.header().path("alg").asString(null))) {
            return failed(claims, MandateProblem.WRONG_TYPE, "The token is not an EdDSA " + MandateClaims.TYPE);
        }
        if (!issuers.contains(claims.issuer())) {
            return failed(claims, MandateProblem.UNTRUSTED_ISSUER, "The issuer " + claims.issuer() + " is not trusted");
        }
        String keyId = parsed.header().path("kid").asString("");
        Optional<PublicKey> key;
        try {
            key = keys.resolve(claims.issuer(), keyId);
        } catch (RuntimeException e) {
            return failed(claims, MandateProblem.UNKNOWN_KEY, "The keys of " + claims.issuer()
                    + " cannot be read: " + e.getMessage());
        }
        if (key.isEmpty()) {
            return failed(claims, MandateProblem.UNKNOWN_KEY, "The issuer has no key " + keyId);
        }
        if (!parsed.verify(key.get())) {
            return failed(claims, MandateProblem.BAD_SIGNATURE, "The signature does not match the issuer's key");
        }
        Instant now = clock.instant();
        if (now.plus(clockSkew).isBefore(claims.notBefore())) {
            problems.add(new MandateCheck.Problem(MandateProblem.NOT_YET_VALID,
                    "The mandate is valid from " + claims.notBefore()));
        }
        if (!now.minus(clockSkew).isBefore(claims.expiresAt())) {
            problems.add(new MandateCheck.Problem(MandateProblem.EXPIRED,
                    "The mandate expired at " + claims.expiresAt()));
        }
        if (audience != null && !audience.equals(claims.audience())) {
            problems.add(new MandateCheck.Problem(MandateProblem.WRONG_AUDIENCE,
                    "The mandate is for " + claims.audience() + ", not " + audience));
        }
        MandateCheck.Problem binding = keyBinding(sdJwt, claims, nonce, now);
        if (binding != null) {
            problems.add(binding);
        }
        if (coverage && !claims.covers(action, target)) {
            problems.add(new MandateCheck.Problem(MandateProblem.NOT_COVERED,
                    "The mandate does not cover " + action + " on " + target));
        }
        if (statusLists != null) {
            problems.addAll(status(claims));
        }
        return new MandateCheck(claims, problems);
    }

    private MandateCheck.Problem keyBinding(SdJwt sdJwt, MandateClaims claims, String nonce, Instant now) {
        if (!claims.bound()) {
            if (sdJwt.keyBinding() != null) {
                return new MandateCheck.Problem(MandateProblem.KEY_BINDING_INVALID,
                        "The mandate names no agent key, so it cannot carry a key binding");
            }
            return requireKeyBinding ? new MandateCheck.Problem(MandateProblem.KEY_NOT_BOUND,
                    "The mandate is not bound to a key of the agent") : null;
        }
        if (sdJwt.keyBinding() == null) {
            return new MandateCheck.Problem(MandateProblem.KEY_BINDING_MISSING,
                    "The mandate is bound to a key of the agent and needs a key binding");
        }
        String problem = KeyBinding.problem(sdJwt, claims.holderKey(), audience != null ? audience
                : claims.audience(), nonce, now, clockSkew, keyBindingMaxAge);
        return problem == null ? null : new MandateCheck.Problem(MandateProblem.KEY_BINDING_INVALID, problem);
    }

    private List<MandateCheck.Problem> status(MandateClaims claims) {
        if (!claims.statusListUrl().startsWith(claims.issuer() + "/")) {
            return List.of(new MandateCheck.Problem(MandateProblem.STATUS_UNAVAILABLE,
                    "The status list " + claims.statusListUrl() + " is not served by the issuer"));
        }
        try {
            StatusList list = statusLists.resolve(claims.issuer(), claims.statusListUrl());
            return list.isRevoked(claims.statusIndex())
                    ? List.of(new MandateCheck.Problem(MandateProblem.REVOKED, "The mandate has been revoked"))
                    : List.of();
        } catch (RuntimeException e) {
            return List.of(new MandateCheck.Problem(MandateProblem.STATUS_UNAVAILABLE,
                    "The revocation status cannot be checked: " + e.getMessage()));
        }
    }

    private static MandateCheck failed(MandateClaims claims, MandateProblem code, String message) {
        return new MandateCheck(claims, List.of(new MandateCheck.Problem(code, message)));
    }

    public static final class Builder {

        private final Set<String> issuers = new LinkedHashSet<>();
        private KeyResolver keys;
        private StatusListResolver statusLists;
        private HttpFetcher http;
        private boolean skipStatus;
        private String audience;
        private Clock clock = Clock.systemUTC();
        private Duration clockSkew = Duration.ofSeconds(60);
        private Duration cacheTtl = Duration.ofMinutes(5);
        private Duration timeout = Duration.ofSeconds(10);
        private Duration keyBindingMaxAge = Duration.ofMinutes(5);
        private boolean requireKeyBinding;

        private Builder() {
        }

        public Builder trustIssuer(String issuer) {
            issuers.add(issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer);
            return this;
        }

        public Builder keys(KeyResolver keys) {
            this.keys = keys;
            return this;
        }

        public Builder statusLists(StatusListResolver statusLists) {
            this.statusLists = statusLists;
            return this;
        }

        public Builder http(HttpFetcher http) {
            this.http = http;
            return this;
        }

        public Builder skipStatus() {
            this.skipStatus = true;
            return this;
        }

        public Builder audience(String audience) {
            this.audience = audience;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder clockSkew(Duration clockSkew) {
            this.clockSkew = clockSkew;
            return this;
        }

        public Builder cacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
            return this;
        }

        public Builder requireKeyBinding() {
            this.requireKeyBinding = true;
            return this;
        }

        public Builder keyBindingMaxAge(Duration keyBindingMaxAge) {
            this.keyBindingMaxAge = keyBindingMaxAge;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public MandateVerifier build() {
            if (issuers.isEmpty()) {
                throw new IllegalStateException("At least one trusted issuer is required");
            }
            return new MandateVerifier(this);
        }
    }
}
