package com.nexusphere.ledger.chain;

import java.util.Objects;

public record SignedCheckpoint(Checkpoint checkpoint, String signature) {

    public SignedCheckpoint {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(signature, "signature");
    }

    public boolean verify(SigningKeys.PublicKeyInfo key) {
        return key.keyId().equals(checkpoint.keyId())
                && SigningKeys.verify(key.publicKey(), checkpoint.signedBytes(), signature);
    }
}
