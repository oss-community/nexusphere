package com.nexusphere.ledger.a2a;

import java.util.Map;

record A2aResponse(int status, byte[] body, Map<String, String> headers) {
}
