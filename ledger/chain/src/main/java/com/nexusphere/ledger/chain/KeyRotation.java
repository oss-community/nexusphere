package com.nexusphere.ledger.chain;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record KeyRotation(String keyId, String publicKey, String previousKeyId, Instant activatedAt,
                          String keySignature, String previousKeySignature) {

    public static final String FORMAT = "nexusphere-ledger/key-rotation/v1";

    public KeyRotation {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(previousKeyId, "previousKeyId");
        Objects.requireNonNull(activatedAt, "activatedAt");
        Objects.requireNonNull(keySignature, "keySignature");
        activatedAt = Timestamps.normalize(activatedAt);
    }

    public static KeyRotation issue(SigningKeys.PublicKeyInfo key, PrivateKey privateKey, String previousKeyId,
                                    PrivateKey previousPrivateKey, Instant activatedAt) {
        Instant normalized = Timestamps.normalize(activatedAt);
        byte[] content = signedBytes(key.keyId(), key.encoded(), previousKeyId, normalized);
        return new KeyRotation(key.keyId(), key.encoded(), previousKeyId, normalized,
                SigningKeys.sign(privateKey, content),
                previousPrivateKey == null ? null : SigningKeys.sign(previousPrivateKey, content));
    }

    public boolean endorsed() {
        return previousKeySignature != null;
    }

    public boolean verifiedByKey() {
        SigningKeys.PublicKeyInfo key = key();
        return key != null && SigningKeys.verify(key.publicKey(), content(), keySignature);
    }

    public boolean verifiedByPrevious(SigningKeys.PublicKeyInfo previous) {
        return endorsed() && previous.keyId().equals(previousKeyId) && verifiedByKey()
                && SigningKeys.verify(previous.publicKey(), content(), previousKeySignature);
    }

    public SigningKeys.PublicKeyInfo key() {
        try {
            SigningKeys.PublicKeyInfo key = SigningKeys.PublicKeyInfo.of(SigningKeys.decodePublic(publicKey));
            return key.keyId().equals(keyId) ? key : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private byte[] content() {
        return signedBytes(keyId, publicKey, previousKeyId, activatedAt);
    }

    private static byte[] signedBytes(String keyId, String publicKey, String previousKeyId, Instant activatedAt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", FORMAT);
        content.put("keyId", keyId);
        content.put("algorithm", SigningKeys.ALGORITHM);
        content.put("publicKey", publicKey);
        content.put("previousKeyId", previousKeyId);
        content.put("activatedAt", Timestamps.format(activatedAt));
        return CanonicalJson.bytes(content);
    }
}
