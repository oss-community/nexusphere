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
    private static final String PRINCIPAL_PATH = "/api/v1/principal";
    private static final String UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"A valid ledger API key is required.\"}";
    private static final String PRINCIPAL_UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"A valid principal token is required.\"}";
    private static final String PRINCIPAL_FORBIDDEN_BODY =
            "{\"code\":\"FORBIDDEN\",\"message\":\"A principal token only opens " + PRINCIPAL_PATH + ".\"}";

    private final byte[] operatorKey;
    private final AgentCredentials agents;
    private final PrincipalTokens principals;

    ApiKeyFilter(LedgerProperties properties, AgentCredentials agents, PrincipalTokens principals) {
        String key = properties.security() == null ? null : properties.security().apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("ledger.security.api-key must be set");
        }
        this.operatorKey = key.getBytes(StandardCharsets.UTF_8);
        this.agents = agents;
        this.principals = principals;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") && !uri.startsWith("/mcp/") && !uri.startsWith("/a2a/out/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Caller> caller = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        boolean principalPath = request.getRequestURI().equals(PRINCIPAL_PATH)
                || request.getRequestURI().startsWith(PRINCIPAL_PATH + "/");
        if (caller.isPresent() && caller.get().role() == Caller.Role.PRINCIPAL && !principalPath) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, PRINCIPAL_FORBIDDEN_BODY);
            return;
        }
        if (caller.isPresent()) {
            request.setAttribute(Caller.ATTRIBUTE, caller.get());
            chain.doFilter(request, response);
            return;
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                principalPath && principals.enabled() ? PRINCIPAL_UNAUTHORIZED_BODY : UNAUTHORIZED_BODY);
    }

    private static void reject(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(body);
    }

    private Optional<Caller> authenticate(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        byte[] presented = header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(operatorKey, presented)) {
            return Optional.of(Caller.operator());
        }
        Optional<Caller> agent = agents.activeAgentByKeyHash(Hashes.sha256(presented)).map(Caller::agent);
        if (agent.isPresent()) {
            return agent;
        }
        return principals.authenticate(header.substring(BEARER.length())).map(Caller::principal);
    }
}
