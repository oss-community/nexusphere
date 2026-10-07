package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.SigningKeys;

import java.security.PublicKey;
import java.util.Optional;

@FunctionalInterface
public interface KeyResolver {

    Optional<PublicKey> resolve(String issuer, String keyId);

    static KeyResolver fixed(String issuer, PublicKey key) {
        String keyId = SigningKeys.keyIdOf(key);
        return (i, k) -> issuer.equals(i) && keyId.equals(k) ? Optional.of(key) : Optional.empty();
    }
}
