package com.nexusphere.ledger.mandate;

import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Mandates {

    private Mandates() {
    }

    public static String issue(MandateClaims claims, String keyId, PrivateKey key) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", Jws.ALGORITHM);
        header.put("typ", MandateClaims.TYPE);
        header.put("kid", keyId);
        return Jws.sign(header, claims.toPayload(), key);
    }
}
