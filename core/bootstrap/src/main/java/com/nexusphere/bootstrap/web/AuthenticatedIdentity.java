package com.nexusphere.bootstrap.web;

import com.nexusphere.bootstrap.security.LocalTokenIssuer;
import com.nexusphere.shared.id.IdentityId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

final class AuthenticatedIdentity {

    private AuthenticatedIdentity() {
    }

    static Optional<IdentityId> current() {
        return token().filter(token -> !operator(token)).map(token -> IdentityId.of(token.getSubject()));
    }

    static boolean operator() {
        return token().filter(AuthenticatedIdentity::operator).isPresent();
    }

    private static boolean operator(Jwt token) {
        return LocalTokenIssuer.OPERATOR_SUBJECT.equals(token.getSubject())
                && Boolean.TRUE.equals(token.getClaimAsBoolean(LocalTokenIssuer.OPERATOR_CLAIM));
    }

    private static Optional<Jwt> token() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            return Optional.of(token.getToken());
        }
        return Optional.empty();
    }
}
