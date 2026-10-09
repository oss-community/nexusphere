package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.server.config.LedgerProperties;

import java.security.PrivateKey;

final class LocalSigningProvider implements SigningProvider {

    private final SigningKeys.PublicKeyInfo publicKey;
    private final Signer signer;

    LocalSigningProvider(LedgerProperties.Signing signing) {
        if (signing == null || isBlank(signing.privateKey()) || isBlank(signing.publicKey())) {
            throw new IllegalStateException("ledger.signing.private-key and ledger.signing.public-key must be set");
        }
        PrivateKey privateKey = SigningKeys.decodePrivate(signing.privateKey());
        this.publicKey = SigningKeys.PublicKeyInfo.of(SigningKeys.decodePublic(signing.publicKey()));
        if (!SigningKeys.matches(privateKey, publicKey.publicKey())) {
            throw new IllegalStateException("ledger.signing.public-key does not belong to ledger.signing.private-key");
        }
        this.signer = Signer.of(privateKey);
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public SigningKeys.PublicKeyInfo publicKey() {
        return publicKey;
    }

    @Override
    public Signer signer() {
        return signer;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
