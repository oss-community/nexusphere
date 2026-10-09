package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.KeyRevocation;
import com.nexusphere.ledger.chain.KeyRotation;
import com.nexusphere.ledger.chain.SigningKeys;

import java.time.Instant;

public record SigningKey(SigningKeys.PublicKeyInfo publicKey, Instant activatedAt, Instant retiredAt,
                         KeyRotation rotation, Long evidenceSequence, KeyRevocation revocation) {

    public SigningKey(SigningKeys.PublicKeyInfo publicKey, Instant activatedAt, Instant retiredAt,
                      KeyRotation rotation, Long evidenceSequence) {
        this(publicKey, activatedAt, retiredAt, rotation, evidenceSequence, null);
    }

    public String keyId() {
        return publicKey.keyId();
    }

    public boolean active() {
        return retiredAt == null;
    }

    public boolean revoked() {
        return revocation != null;
    }

    public String status() {
        return revoked() ? "REVOKED" : active() ? "ACTIVE" : "RETIRED";
    }

    public String previousKeyId() {
        return rotation == null ? null : rotation.previousKeyId();
    }
}
