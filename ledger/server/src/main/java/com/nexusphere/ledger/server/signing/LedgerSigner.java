package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.Checkpoint;
import com.nexusphere.ledger.chain.SignedCheckpoint;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.mandate.MandateClaims;
import com.nexusphere.ledger.mandate.Mandates;
import com.nexusphere.ledger.mandate.StatusList;
import com.nexusphere.ledger.server.config.LedgerProperties;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.time.Instant;

@Component
public class LedgerSigner {

    private final PrivateKey privateKey;
    private final SigningKeys.PublicKeyInfo publicKey;

    LedgerSigner(LedgerProperties properties) {
        LedgerProperties.Signing signing = properties.signing();
        if (signing == null || isBlank(signing.privateKey()) || isBlank(signing.publicKey())) {
            throw new IllegalStateException("ledger.signing.private-key and ledger.signing.public-key must be set");
        }
        this.privateKey = SigningKeys.decodePrivate(signing.privateKey());
        this.publicKey = SigningKeys.PublicKeyInfo.of(SigningKeys.decodePublic(signing.publicKey()));
        if (!SigningKeys.matches(privateKey, publicKey.publicKey())) {
            throw new IllegalStateException("ledger.signing.public-key does not belong to ledger.signing.private-key");
        }
    }

    public SignedCheckpoint sign(long sequence, String headHash, Instant createdAt) {
        Checkpoint checkpoint = new Checkpoint(sequence, headHash, createdAt, publicKey.keyId());
        return new SignedCheckpoint(checkpoint, SigningKeys.sign(privateKey, checkpoint.signedBytes()));
    }

    public String signMandate(MandateClaims claims) {
        return Mandates.issue(claims, publicKey.keyId(), privateKey);
    }

    public String signStatusList(StatusList statusList) {
        return statusList.sign(publicKey.keyId(), privateKey);
    }

    public SigningKeys.PublicKeyInfo publicKey() {
        return publicKey;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
