package com.nexusphere.bootstrap.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusphere.shared.error.ErrorCategory;

import java.util.Map;

/**
 * The single error body of the public API (redesign §81). Never carries stack traces,
 * SQL or other infrastructure details.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String code,
        ErrorCategory category,
        String message,
        String correlationId,
        Map<String, Object> details) {
}
