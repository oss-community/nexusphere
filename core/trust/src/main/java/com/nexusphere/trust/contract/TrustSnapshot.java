package com.nexusphere.trust.contract;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record TrustSnapshot(UUID id, PartyReference source, PartyReference target, Set<String> scopes, String level,
                            Instant effectiveFrom, Instant effectiveUntil) {
}
