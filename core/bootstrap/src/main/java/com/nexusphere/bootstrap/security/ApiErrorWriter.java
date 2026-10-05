package com.nexusphere.bootstrap.security;

import com.nexusphere.bootstrap.web.ApiError;
import com.nexusphere.bootstrap.web.RequestCorrelation;
import com.nexusphere.shared.error.ErrorCategory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

class ApiErrorWriter {

    private final JsonMapper json;

    ApiErrorWriter(JsonMapper json) {
        this.json = json;
    }

    void write(HttpServletRequest request, HttpServletResponse response, int status, String code,
               ErrorCategory category, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = new ApiError(code, category, message, RequestCorrelation.of(request).value(), Map.of());
        json.writeValue(response.getOutputStream(), error);
    }
}
