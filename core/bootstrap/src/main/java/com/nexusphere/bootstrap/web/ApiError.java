package com.nexusphere.bootstrap.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusphere.shared.error.ErrorCategory;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        String code,
        ErrorCategory category,
        String message,
        String correlationId,
        Map<String, Object> details) {
}
