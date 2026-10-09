package com.nexusphere.bootstrap.web;

import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static HttpStatus statusOf(ErrorCategory category) {
        return switch (category) {
            case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case AUTHENTICATION_ERROR -> HttpStatus.UNAUTHORIZED;
            case AUTHORIZATION_ERROR -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BUSINESS_RULE_VIOLATION, FEDERATION_ERROR, DELEGATION_ERROR, AGREEMENT_ERROR, TRANSACTION_ERROR ->
                    HttpStatus.UNPROCESSABLE_CONTENT;
            case RATE_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case INFRASTRUCTURE_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> domain(DomainException e, HttpServletRequest request) {
        return respond(statusOf(e.category()), e.code(), e.category(), e.getMessage(), e.details(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ErrorCategory.VALIDATION_ERROR,
                "The request has invalid fields.", Map.of("fields", fields), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> malformed(Exception e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", ErrorCategory.VALIDATION_ERROR,
                "The request could not be read.", Map.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", ErrorCategory.NOT_FOUND,
                "No resource exists at this path.", Map.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ErrorCategory.VALIDATION_ERROR,
                "Method " + e.getMethod() + " is not allowed here.", Map.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", ErrorCategory.VALIDATION_ERROR,
                "The content type is not supported.", Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ErrorCategory.INFRASTRUCTURE_ERROR,
                "An unexpected error occurred.", Map.of(), request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, ErrorCategory category, String message,
                                             Map<String, Object> details, HttpServletRequest request) {
        String correlationId = RequestCorrelation.of(request).value();
        return ResponseEntity.status(status).body(new ApiError(code, category, message, correlationId, details));
    }
}
