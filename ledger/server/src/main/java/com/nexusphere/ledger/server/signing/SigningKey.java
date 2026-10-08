package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.KeyRotation;
import com.nexusphere.ledger.chain.SigningKeys;

import java.time.Instant;

public record SigningKey(SigningKeys.PublicKeyInfo publicKey, Instant activatedAt, Instant retiredAt,
                         KeyRotation rotation, Long evidenceSequence) {

    public String keyId() {
        return publicKey.keyId();
    }

    public boolean active() {
        return retiredAt == null;
    }

    public String previousKeyId() {
        return rotation == null ? null : rotation.previousKeyId();
    }
}
