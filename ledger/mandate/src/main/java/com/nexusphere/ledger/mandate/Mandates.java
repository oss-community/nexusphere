package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.Signer;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Mandates {

    private Mandates() {
    }

    public static String issue(MandateClaims claims, String keyId, PrivateKey key) {
        return issue(claims, keyId, Signer.of(key));
    }

    @SuppressWarnings("unchecked")
    public static String issue(MandateClaims claims, String keyId, Signer key) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", Jws.ALGORITHM);
        header.put("typ", MandateClaims.TYPE);
        header.put("kid", keyId);
        Map<String, Object> payload = claims.toPayload();
        Map<String, Object> mandate = (Map<String, Object>) payload.get("mandate");
        List<SdJwt.Disclosure> disclosures = new ArrayList<>();
        for (String name : MandateClaims.SELECTIVE) {
            Object value = mandate.remove(name);
            if (value != null) {
                disclosures.add(SdJwt.Disclosure.of(name, value));
            }
        }
        mandate.put("_sd", SdJwt.digests(disclosures));
        payload.put("_sd_alg", SdJwt.HASH_ALGORITHM);
        return SdJwt.issue(header, payload, disclosures, key);
    }

    public static MandateClaims claims(String token) {
        return MandateClaims.fromPayload(SdJwt.parse(token).claims());
    }
}
