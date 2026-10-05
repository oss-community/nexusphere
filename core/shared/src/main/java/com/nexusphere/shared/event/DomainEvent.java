package com.nexusphere.shared.event;

import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    Optional<NetworkId> networkId();

    default String eventType() {
        String pkg = getClass().getPackageName();
        String prefix = "com.nexusphere.";
        String module = pkg.startsWith(prefix) ? pkg.substring(prefix.length()).split("\\.")[0] : pkg;
        return module + "." + getClass().getSimpleName();
    }

    default int eventVersion() {
        return 1;
    }
}
