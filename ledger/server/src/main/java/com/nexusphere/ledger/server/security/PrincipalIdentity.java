package com.nexusphere.ledger.server.security;

import java.time.Instant;

public record PrincipalIdentity(String principalId, String issuer, String subject, String name, String tokenHash,
                                Instant authTime, Instant expiresAt) {
}
