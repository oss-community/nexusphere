package com.nexusphere.bootstrap.web;

import com.nexusphere.shared.context.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;

/** Access to the correlation ID assigned by {@link CorrelationIdFilter}. */
public final class RequestCorrelation {

    public static final String HEADER = "X-Correlation-Id";
    static final String ATTRIBUTE = RequestCorrelation.class.getName();

    private RequestCorrelation() {
    }

    public static CorrelationId of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof CorrelationId id ? id : CorrelationId.fromNullable(request.getHeader(HEADER));
    }
}
