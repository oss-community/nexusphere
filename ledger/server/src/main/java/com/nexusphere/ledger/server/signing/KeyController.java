package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.KeyRevocation;
import com.nexusphere.ledger.chain.KeyRotation;
import com.nexusphere.ledger.chain.SigningKeys;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/keys")
public class KeyController {

    public record RotationResponse(String format, String previousKeyId, String keySignature,
                                   String previousKeySignature) {

        static RotationResponse of(KeyRotation rotation) {
            return rotation == null ? null : new RotationResponse(KeyRotation.FORMAT, rotation.previousKeyId(),
                    rotation.keySignature(), rotation.previousKeySignature());
        }
    }

    public record RevocationResponse(String format, Instant compromisedAt, Instant revokedAt, String reason,
                                     String revokerKeyId, String signature) {

        public static RevocationResponse of(KeyRevocation revocation) {
            return revocation == null ? null : new RevocationResponse(KeyRevocation.FORMAT,
                    revocation.compromisedAt(), revocation.revokedAt(), revocation.reason(),
                    revocation.revokerKeyId(), revocation.signature());
        }
    }

    public record KeyResponse(String keyId, String algorithm, String publicKey, String status, Instant activatedAt,
                              Instant retiredAt, RotationResponse rotation, RevocationResponse revocation) {

        public static KeyResponse of(SigningKey key) {
            return new KeyResponse(key.keyId(), SigningKeys.ALGORITHM, key.publicKey().encoded(), key.status(),
                    key.activatedAt(), key.retiredAt(), RotationResponse.of(key.rotation()),
                    RevocationResponse.of(key.revocation()));
        }
    }

    private final LedgerSigner signer;

    KeyController(LedgerSigner signer) {
        this.signer = signer;
    }

    @GetMapping
    List<KeyResponse> keys() {
        return signer.keys().stream().map(KeyResponse::of).toList();
    }
}
