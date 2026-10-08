package com.nexusphere.ledger.mcp;

import java.io.InputStream;

record UpstreamResponse(int status, String sessionId, byte[] body, InputStream stream) {

    boolean streamed() {
        return stream != null;
    }
}
