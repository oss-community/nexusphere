package com.nexusphere.bootstrap.web;

import com.nexusphere.shared.id.IdentityId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

final class AuthenticatedIdentity {

    private AuthenticatedIdentity() {
    }

    static Optional<IdentityId> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
            return Optional.of(IdentityId.of(token.getToken().getSubject()));
        }
        return Optional.empty();
    }
}
