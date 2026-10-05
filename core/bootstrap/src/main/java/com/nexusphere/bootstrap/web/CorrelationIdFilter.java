package com.nexusphere.bootstrap.web;

import com.nexusphere.shared.context.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gives every request a correlation ID (redesign §73): the client's {@code X-Correlation-Id}
 * when well formed, otherwise a new one. It is returned in the response header, put in the
 * logging MDC and made available to handlers through {@link RequestCorrelation}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationId correlationId = CorrelationId.fromNullable(request.getHeader(RequestCorrelation.HEADER));
        request.setAttribute(RequestCorrelation.ATTRIBUTE, correlationId);
        response.setHeader(RequestCorrelation.HEADER, correlationId.value());
        MDC.put(MDC_KEY, correlationId.value());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
