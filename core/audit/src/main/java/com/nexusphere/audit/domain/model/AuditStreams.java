package com.nexusphere.audit.domain.model;

import com.nexusphere.shared.id.NetworkId;

import java.util.UUID;

public final class AuditStreams {

    public static final NetworkId PLATFORM = new NetworkId(new UUID(0L, 0L));

    private AuditStreams() {
    }
}
