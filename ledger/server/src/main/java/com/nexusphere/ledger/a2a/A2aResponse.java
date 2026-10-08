package com.nexusphere.ledger.a2a;

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

record A2aResponse(int status, byte[] body, Map<String, String> headers, StreamingResponseBody stream) {

    A2aResponse(int status, byte[] body, Map<String, String> headers) {
        this(status, body, headers, null);
    }
}
