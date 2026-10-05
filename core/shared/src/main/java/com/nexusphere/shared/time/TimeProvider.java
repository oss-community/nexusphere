package com.nexusphere.shared.time;

import java.time.Instant;

/** The only source of "now" for domain and application code, so expiry rules are testable. */
@FunctionalInterface
public interface TimeProvider {

    Instant now();
}
