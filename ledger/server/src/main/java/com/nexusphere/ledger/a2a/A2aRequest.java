package com.nexusphere.ledger.a2a;

import java.net.URI;
import java.util.function.Function;

record A2aRequest(String method, URI uri, Function<String, String> header) {
}
