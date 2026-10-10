package com.nexusphere.ledger.chain;

import java.util.Objects;

public record SignedCheckpoint(Checkpoint checkpoint, String signature, String timestamp) {

    public SignedCheckpoint {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(signature, "signature");
    }

    public SignedCheckpoint(Checkpoint checkpoint, String signature) {
        this(checkpoint, signature, null);
    }

    public SignedCheckpoint withTimestamp(String timestamp) {
        return new SignedCheckpoint(checkpoint, signature, timestamp);
    }

    public byte[] timestampImprint() {
        return Hashes.sha256Bytes(checkpoint.signedBytes());
    }

    public boolean verify(SigningKeys.PublicKeyInfo key) {
        return key.keyId().equals(checkpoint.keyId())
                && SigningKeys.verify(key.publicKey(), checkpoint.signedBytes(), signature);
    }
}
