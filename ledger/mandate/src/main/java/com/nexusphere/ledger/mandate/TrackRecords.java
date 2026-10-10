package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.Signer;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrackRecords {

    private TrackRecords() {
    }

    public static String issue(TrackRecordClaims claims, String keyId, PrivateKey key) {
        return issue(claims, keyId, Signer.of(key));
    }

    @SuppressWarnings("unchecked")
    public static String issue(TrackRecordClaims claims, String keyId, Signer key) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", Jws.ALGORITHM);
        header.put("typ", TrackRecordClaims.TYPE);
        header.put("kid", keyId);
        Map<String, Object> payload = claims.toPayload();
        Map<String, Object> record = (Map<String, Object>) payload.get("record");
        List<SdJwt.Disclosure> disclosures = new ArrayList<>();
        for (String name : TrackRecordClaims.SELECTIVE) {
            Object value = record.remove(name);
            if (value != null) {
                disclosures.add(SdJwt.Disclosure.of(name, value));
            }
        }
        record.put("_sd", SdJwt.digests(disclosures));
        payload.put("_sd_alg", SdJwt.HASH_ALGORITHM);
        return SdJwt.issue(header, payload, disclosures, key);
    }

    public static TrackRecordClaims claims(String token) {
        return TrackRecordClaims.fromPayload(SdJwt.parse(token).claims());
    }
}
