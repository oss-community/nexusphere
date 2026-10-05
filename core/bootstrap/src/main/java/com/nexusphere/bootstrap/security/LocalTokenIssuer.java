package com.nexusphere.bootstrap.security;

import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;

public class LocalTokenIssuer {

    public record IssuedToken(String accessToken, String tokenType, long expiresIn) {
    }

    private final JwtEncoder encoder;
    private final TokenProperties properties;
    private final TimeProvider time;

    LocalTokenIssuer(JwtEncoder encoder, TokenProperties properties, TimeProvider time) {
        this.encoder = encoder;
        this.properties = properties;
        this.time = time;
    }

    public IssuedToken issue(IdentityId identityId) {
        Instant now = time.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(identityId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, "Bearer", properties.ttl().toSeconds());
    }
}
