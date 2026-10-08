package com.nexusphere.ledger.authorization.domain.model;

import java.time.Instant;

public record ConsentProof(String principalId, String issuer, String subject, String tokenHash, Instant authTime) {
}
