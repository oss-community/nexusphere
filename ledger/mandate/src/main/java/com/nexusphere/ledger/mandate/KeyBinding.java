package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.Signer;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KeyBinding {

    public static final String TYPE = "kb+jwt";

    private KeyBinding() {
    }

    public static String present(String presentation, PrivateKey holder, String audience, String nonce,
                                 Instant issuedAt) {
        return present(presentation, Signer.of(holder), audience, nonce, issuedAt);
    }

    public static String present(String presentation, Signer holder, String audience, String nonce,
                                 Instant issuedAt) {
        SdJwt parsed = SdJwt.parse(presentation);
        if (parsed.keyBinding() != null) {
            throw new IllegalArgumentException("The presentation already has a key binding");
        }
        if (audience == null || audience.isBlank() || nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("A key binding needs an audience and a nonce");
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", Jws.ALGORITHM);
        header.put("typ", TYPE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("aud", audience);
        payload.put("nonce", nonce);
        payload.put("sd_hash", sdHash(parsed.presentation()));
        return parsed.presentation() + Jws.sign(header, payload, holder);
    }

    public static String sdHash(String presentation) {
        return Jws.encode(HexFormat.of().parseHex(Hashes.sha256(presentation.getBytes(StandardCharsets.US_ASCII))));
    }

    static String problem(SdJwt token, PublicKey holderKey, String audience, String nonce, Instant now,
                          Duration clockSkew, Duration maxAge) {
        Jws.Parsed kb;
        try {
            kb = Jws.parse(token.keyBinding());
        } catch (IllegalArgumentException e) {
            return "The key binding cannot be read: " + e.getMessage();
        }
        if (!TYPE.equals(kb.header().path("typ").asString(null))
                || !Jws.ALGORITHM.equals(kb.header().path("alg").asString(null))) {
            return "The key binding is not an EdDSA " + TYPE;
        }
        if (!kb.verify(holderKey)) {
            return "The key binding is not signed by the agent's key";
        }
        JsonNode payload = kb.payload();
        if (!sdHash(token.presentation()).equals(payload.path("sd_hash").asString(null))) {
            return "The key binding is for another presentation";
        }
        if (audience != null && !audience.equals(payload.path("aud").asString(null))) {
            return "The key binding is for " + payload.path("aud").asString("no audience") + ", not " + audience;
        }
        if (!payload.path("nonce").isString() || payload.path("nonce").asString().isBlank()) {
            return "The key binding has no nonce";
        }
        if (nonce != null && !nonce.equals(payload.path("nonce").asString())) {
            return "The key binding is for another nonce";
        }
        if (!payload.path("iat").isNumber()) {
            return "The key binding has no iat";
        }
        Instant issuedAt = Instant.ofEpochSecond(payload.path("iat").asLong());
        if (issuedAt.isAfter(now.plus(clockSkew)) || issuedAt.isBefore(now.minus(maxAge).minus(clockSkew))) {
            return "The key binding was made at " + issuedAt + ", outside the accepted window";
        }
        return null;
    }
}
