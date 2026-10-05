package com.nexusphere.authorization.contract;

import java.util.Set;
import java.util.UUID;

public record FederationContext(UUID federationId, Set<String> scopes) {
}
