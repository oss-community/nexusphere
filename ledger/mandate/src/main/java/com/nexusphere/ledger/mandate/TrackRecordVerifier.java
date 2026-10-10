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

public final class TrackRecordVerifier {

    private final Set<String> issuers;
    private final KeyResolver keys;
    private final String audience;
    private final Clock clock;
    private final Duration clockSkew;
    private final Duration keyBindingMaxAge;
    private final boolean requireKeyBinding;

    private TrackRecordVerifier(Builder builder) {
        this.issuers = Set.copyOf(builder.issuers);
        this.clock = builder.clock;
        this.clockSkew = builder.clockSkew;
        this.keyBindingMaxAge = builder.keyBindingMaxAge;
        this.requireKeyBinding = builder.requireKeyBinding;
        this.audience = builder.audience;
        this.keys = builder.keys == null ? new JwksKeyResolver(builder.http == null
                ? HttpFetcher.create(builder.timeout) : builder.http, clock, builder.cacheTtl) : builder.keys;
    }

    public static Builder builder() {
        return new Builder();
    }

    public TrackRecordCheck verify(String token) {
        return verifyBound(token, null);
    }

    public TrackRecordCheck verifyBound(String token, String nonce) {
        List<MandateCheck.Problem> problems = new ArrayList<>();
        SdJwt sdJwt;
        TrackRecordClaims claims;
        try {
            sdJwt = SdJwt.parse(token);
            claims = TrackRecordClaims.fromPayload(sdJwt.claims());
        } catch (IllegalArgumentException e) {
            return failed(null, MandateProblem.MALFORMED, e.getMessage());
        }
        Jws.Parsed parsed = sdJwt.jwt();
        if (!TrackRecordClaims.TYPE.equals(parsed.header().path("typ").asString(null))
                || !Jws.ALGORITHM.equals(parsed.header().path("alg").asString(null))) {
            return failed(claims, MandateProblem.WRONG_TYPE, "The token is not an EdDSA " + TrackRecordClaims.TYPE);
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
        if (now.plus(clockSkew).isBefore(claims.issuedAt())) {
            problems.add(new MandateCheck.Problem(MandateProblem.NOT_YET_VALID,
                    "The track record is valid from " + claims.issuedAt()));
        }
        if (!now.minus(clockSkew).isBefore(claims.expiresAt())) {
            problems.add(new MandateCheck.Problem(MandateProblem.EXPIRED,
                    "The track record expired at " + claims.expiresAt()));
        }
        if (audience != null && claims.audience() != null && !audience.equals(claims.audience())) {
            problems.add(new MandateCheck.Problem(MandateProblem.WRONG_AUDIENCE,
                    "The track record is for " + claims.audience() + ", not " + audience));
        }
        MandateCheck.Problem binding = keyBinding(sdJwt, claims, nonce, now);
        if (binding != null) {
            problems.add(binding);
        }
        return new TrackRecordCheck(claims, problems);
    }

    private MandateCheck.Problem keyBinding(SdJwt sdJwt, TrackRecordClaims claims, String nonce, Instant now) {
        if (!claims.bound()) {
            if (sdJwt.keyBinding() != null) {
                return new MandateCheck.Problem(MandateProblem.KEY_BINDING_INVALID,
                        "The track record names no agent key, so it cannot carry a key binding");
            }
            return requireKeyBinding ? new MandateCheck.Problem(MandateProblem.KEY_NOT_BOUND,
                    "The track record is not bound to a key of the agent") : null;
        }
        if (sdJwt.keyBinding() == null) {
            return new MandateCheck.Problem(MandateProblem.KEY_BINDING_MISSING,
                    "The track record is bound to a key of the agent and needs a key binding");
        }
        String problem = KeyBinding.problem(sdJwt, claims.holderKey(), audience != null ? audience
                : claims.audience(), nonce, now, clockSkew, keyBindingMaxAge);
        return problem == null ? null : new MandateCheck.Problem(MandateProblem.KEY_BINDING_INVALID, problem);
    }

    private static TrackRecordCheck failed(TrackRecordClaims claims, MandateProblem code, String message) {
        return new TrackRecordCheck(claims, List.of(new MandateCheck.Problem(code, message)));
    }

    public static final class Builder {

        private final Set<String> issuers = new LinkedHashSet<>();
        private KeyResolver keys;
        private HttpFetcher http;
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

        public Builder http(HttpFetcher http) {
            this.http = http;
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

        public TrackRecordVerifier build() {
            if (issuers.isEmpty()) {
                throw new IllegalStateException("At least one trusted issuer is required");
            }
            return new TrackRecordVerifier(this);
        }
    }
}
