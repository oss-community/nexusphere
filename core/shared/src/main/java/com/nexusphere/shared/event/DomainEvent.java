package com.nexusphere.shared.event;

import com.nexusphere.shared.id.NetworkId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Something that happened in a module's domain. Implementations are immutable records owned by that module. */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    /** The network in which the event happened, if it is network-scoped. */
    Optional<NetworkId> networkId();

    /** Stable name such as {@code network.NetworkCreated}: the owning module plus the simple class name. */
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
