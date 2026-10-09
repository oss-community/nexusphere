package com.nexusphere.bootstrap.web;

import com.nexusphere.shared.error.ErrorCategory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final JsonMapper json;

    RateLimitFilter(RateLimitProperties properties, JsonMapper json) {
        this.limiter = new RateLimiter(properties.limit(), properties.bucket(), System::nanoTime);
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !limiter.enabled() || request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long wait = limiter.acquire(key(request));
        if (wait == 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(wait, 1)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError("RATE_LIMIT_EXCEEDED",
                ErrorCategory.RATE_LIMIT_EXCEEDED, "Too many requests; retry after " + Math.max(wait, 1) + "s",
                RequestCorrelation.of(request).value(), Map.of()));
    }

    static String key(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return "ip:" + request.getRemoteAddr();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(authorization.getBytes(StandardCharsets.UTF_8));
            return "key:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
