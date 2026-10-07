package com.nexusphere.ledger.server.security;

import com.nexusphere.ledger.server.config.LedgerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
class ApiKeyFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final String UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"A valid ledger API key is required.\"}";

    private final byte[] apiKey;

    ApiKeyFilter(LedgerProperties properties) {
        String key = properties.security() == null ? null : properties.security().apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("ledger.security.api-key must be set");
        }
        this.apiKey = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER)
                && MessageDigest.isEqual(apiKey, header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
