package com.nexusphere.ledger.chain;

import java.security.PrivateKey;

@FunctionalInterface
public interface Signer {

    byte[] sign(byte[] data);

    static Signer of(PrivateKey key) {
        return data -> SigningKeys.signRaw(key, data);
    }
}
