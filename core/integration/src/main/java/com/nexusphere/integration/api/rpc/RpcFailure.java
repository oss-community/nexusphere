package com.nexusphere.integration.api.rpc;

import java.util.Map;

class RpcFailure extends RuntimeException {

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private final int code;
    private final Map<String, Object> data;

    RpcFailure(int code, String message, Map<String, Object> data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    RpcFailure(int code, String message) {
        this(code, message, null);
    }

    int code() {
        return code;
    }

    Map<String, Object> data() {
        return data;
    }
}
