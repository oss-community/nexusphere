package com.nexusphere.ledger.chain;

import java.util.Objects;

public record SignedCheckpoint(Checkpoint checkpoint, String signature, String timestamp, String rekor) {

    public SignedCheckpoint {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(signature, "signature");
    }

    public SignedCheckpoint(Checkpoint checkpoint, String signature) {
        this(checkpoint, signature, null, null);
    }

    public SignedCheckpoint(Checkpoint checkpoint, String signature, String timestamp) {
        this(checkpoint, signature, timestamp, null);
    }

    public SignedCheckpoint withTimestamp(String timestamp) {
        return new SignedCheckpoint(checkpoint, signature, timestamp, rekor);
    }

    public SignedCheckpoint withRekor(String rekor) {
        return new SignedCheckpoint(checkpoint, signature, timestamp, rekor);
    }

    public byte[] rekorDigest() {
        return Hashes.sha512Bytes(checkpoint.signedBytes());
    }

    public byte[] timestampImprint() {
        return Hashes.sha256Bytes(checkpoint.signedBytes());
    }

    public boolean verify(SigningKeys.PublicKeyInfo key) {
        return key.keyId().equals(checkpoint.keyId())
                && SigningKeys.verify(key.publicKey(), checkpoint.signedBytes(), signature);
    }
}
