package com.nexusphere.ledger.authorization.domain.model;

import java.time.Instant;
import java.util.UUID;

public record MandateRequest(UUID grantId, String audience, Instant expiresAt) {
}
