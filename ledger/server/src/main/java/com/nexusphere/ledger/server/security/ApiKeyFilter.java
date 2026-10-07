package com.nexusphere.ledger.server.security;

import com.nexusphere.ledger.chain.Hashes;
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
import java.util.Optional;

@Component
class ApiKeyFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final String UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"A valid ledger API key is required.\"}";

    private final byte[] operatorKey;
    private final AgentCredentials agents;

    ApiKeyFilter(LedgerProperties properties, AgentCredentials agents) {
        String key = properties.security() == null ? null : properties.security().apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("ledger.security.api-key must be set");
        }
        this.operatorKey = key.getBytes(StandardCharsets.UTF_8);
        this.agents = agents;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Caller> caller = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (caller.isPresent()) {
            request.setAttribute(Caller.ATTRIBUTE, caller.get());
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getWriter().write(UNAUTHORIZED_BODY);
    }

    private Optional<Caller> authenticate(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        byte[] presented = header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(operatorKey, presented)) {
            return Optional.of(Caller.operator());
        }
        return agents.activeAgentByKeyHash(Hashes.sha256(presented)).map(Caller::agent);
    }
}
