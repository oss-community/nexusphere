package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.chain.SigningKeys;

import java.util.Optional;

public interface SigningProvider {

    String name();

    SigningKeys.PublicKeyInfo publicKey();

    Signer signer();

    default Optional<Signer> signerFor(SigningKeys.PublicKeyInfo previous) {
        return Optional.empty();
    }
}
