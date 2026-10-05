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

    public static final String OPERATOR_SUBJECT = "platform-operator";
    public static final String OPERATOR_CLAIM = "operator";

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
        return issue(claims(identityId.toString()).build());
    }

    public IssuedToken issueOperator() {
        return issue(claims(OPERATOR_SUBJECT).claim(OPERATOR_CLAIM, true).build());
    }

    private JwtClaimsSet.Builder claims(String subject) {
        Instant now = time.now();
        return JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()));
    }

    private IssuedToken issue(JwtClaimsSet claims) {
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, "Bearer", properties.ttl().toSeconds());
    }
}
