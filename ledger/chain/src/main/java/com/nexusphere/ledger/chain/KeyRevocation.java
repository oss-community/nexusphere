package com.nexusphere.ledger.chain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record KeyRevocation(String keyId, Instant compromisedAt, Instant revokedAt, String reason,
                            String revokerKeyId, String signature) {

    public static final String FORMAT = "nexusphere-ledger/key-revocation/v1";

    public KeyRevocation {
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(compromisedAt, "compromisedAt");
        Objects.requireNonNull(revokedAt, "revokedAt");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(revokerKeyId, "revokerKeyId");
        Objects.requireNonNull(signature, "signature");
        compromisedAt = Timestamps.normalize(compromisedAt);
        revokedAt = Timestamps.normalize(revokedAt);
    }

    public static KeyRevocation issue(String keyId, Instant compromisedAt, Instant revokedAt, String reason,
                                      String revokerKeyId, Signer revoker) {
        if (keyId.equals(revokerKeyId)) {
            throw new IllegalArgumentException("A key cannot revoke itself");
        }
        byte[] content = signedBytes(keyId, Timestamps.normalize(compromisedAt), Timestamps.normalize(revokedAt),
                reason, revokerKeyId);
        return new KeyRevocation(keyId, compromisedAt, revokedAt, reason, revokerKeyId,
                SigningKeys.sign(revoker, content));
    }

    public boolean verify(SigningKeys.PublicKeyInfo revoker) {
        return !keyId.equals(revokerKeyId) && revoker.keyId().equals(revokerKeyId)
                && SigningKeys.verify(revoker.publicKey(),
                signedBytes(keyId, compromisedAt, revokedAt, reason, revokerKeyId), signature);
    }

    public boolean covers(Instant time) {
        return !time.isBefore(compromisedAt);
    }

    private static byte[] signedBytes(String keyId, Instant compromisedAt, Instant revokedAt, String reason,
                                      String revokerKeyId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("format", FORMAT);
        content.put("keyId", keyId);
        content.put("compromisedAt", Timestamps.format(compromisedAt));
        content.put("revokedAt", Timestamps.format(revokedAt));
        content.put("reason", reason);
        content.put("revokerKeyId", revokerKeyId);
        return CanonicalJson.bytes(content);
    }
}
