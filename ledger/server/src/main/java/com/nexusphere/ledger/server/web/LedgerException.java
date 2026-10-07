package com.nexusphere.ledger.server.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public LedgerException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public static LedgerException invalid(String message, Map<String, Object> fields) {
        return new LedgerException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, Map.of("fields", fields));
    }

    public static LedgerException notFound(String what) {
        return new LedgerException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " was not found.", Map.of());
    }

    public static LedgerException forbidden(String message) {
        return new LedgerException(HttpStatus.FORBIDDEN, "FORBIDDEN", message, Map.of());
    }

    public static LedgerException conflict(String code, String message) {
        return new LedgerException(HttpStatus.CONFLICT, code, message, Map.of());
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
