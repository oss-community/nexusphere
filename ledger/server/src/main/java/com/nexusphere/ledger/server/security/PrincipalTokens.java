package com.nexusphere.ledger.server.security;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.server.config.LedgerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
class PrincipalTokens {

    private static final Logger log = LoggerFactory.getLogger(PrincipalTokens.class);

    private final LedgerProperties.Oidc oidc;
    private volatile JwtDecoder decoder;

    PrincipalTokens(LedgerProperties properties) {
        this.oidc = properties.consentRequired() ? properties.oidc() : null;
        if (oidc != null && (oidc.audience() == null || oidc.audience().isBlank())) {
            throw new IllegalStateException("ledger.oidc.audience must be set when ledger.oidc.issuer is set");
        }
    }

    boolean enabled() {
        return oidc != null;
    }

    Optional<PrincipalIdentity> authenticate(String token) {
        if (oidc == null || token.chars().filter(c -> c == '.').count() != 2) {
            return Optional.empty();
        }
        try {
            Jwt jwt = decoder().decode(token);
            String claim = oidc.principalClaim() == null || oidc.principalClaim().isBlank() ? "sub"
                    : oidc.principalClaim();
            String principalId = jwt.getClaimAsString(claim);
            if (principalId == null || principalId.isBlank()) {
                log.debug("The principal token has no {} claim", claim);
                return Optional.empty();
            }
            String name = jwt.getClaimAsString("name");
            return Optional.of(new PrincipalIdentity(principalId, oidc.issuer(), jwt.getSubject(),
                    name == null ? principalId : name, Hashes.sha256(token.getBytes(StandardCharsets.UTF_8)),
                    jwt.hasClaim("auth_time") ? jwt.getClaimAsInstant("auth_time") : null, jwt.getExpiresAt()));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("A principal token was rejected: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private JwtDecoder decoder() {
        JwtDecoder current = decoder;
        if (current == null) {
            synchronized (this) {
                if (decoder == null) {
                    decoder = build();
                }
                current = decoder;
            }
        }
        return current;
    }

    private JwtDecoder build() {
        NimbusJwtDecoder.JwkSetUriJwtDecoderBuilder builder = oidc.jwksUri() == null
                ? NimbusJwtDecoder.withIssuerLocation(oidc.issuer())
                : NimbusJwtDecoder.withJwkSetUri(oidc.jwksUri().toString());
        NimbusJwtDecoder built = builder.discoverJwsAlgorithms().validateType(false).build();
        built.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(oidc.issuer()),
                audience(oidc.audience())));
        return built;
    }

    private static OAuth2TokenValidator<Jwt> audience(String audience) {
        return jwt -> {
            boolean listed = jwt.getAudience() != null && jwt.getAudience().contains(audience);
            if (listed || audience.equals(jwt.getClaimAsString("azp"))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                    "The token is not meant for " + audience, null));
        };
    }
}
