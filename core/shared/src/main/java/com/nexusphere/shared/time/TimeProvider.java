package com.nexusphere.shared.time;

import java.time.Instant;

@FunctionalInterface
public interface TimeProvider {

    Instant now();
}
