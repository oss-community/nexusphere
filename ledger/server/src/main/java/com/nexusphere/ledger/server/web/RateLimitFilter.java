package com.nexusphere.ledger.server.web;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.server.config.RateLimitProperties;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    RateLimitFilter(RateLimitProperties properties) {
        this.limiter = new RateLimiter(properties.limit(), properties.bucket(), System::nanoTime);
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
        long seconds = Math.max(wait, 1);
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Too many requests; retry after "
                + seconds + "s.\"}");
    }

    private static String key(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return "ip:" + request.getRemoteAddr();
        }
        return "key:" + Hashes.sha256(authorization.getBytes(StandardCharsets.UTF_8));
    }
}
