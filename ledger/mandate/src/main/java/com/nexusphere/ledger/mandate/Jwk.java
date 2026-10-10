package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.SigningKeys;
import tools.jackson.databind.JsonNode;

import java.security.PublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Jwk {

    private static final byte[] ED25519_X509_PREFIX = Base64.getDecoder().decode("MCowBQYDK2VwAyEA");

    private Jwk() {
    }

    public static Map<String, Object> of(SigningKeys.PublicKeyInfo key) {
        byte[] encoded = key.publicKey().getEncoded();
        byte[] raw = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("kid", key.keyId());
        jwk.put("use", "sig");
        jwk.put("alg", Jws.ALGORITHM);
        jwk.put("x", Jws.encode(raw));
        return jwk;
    }

    public static Map<String, Object> confirmation(PublicKey key) {
        byte[] encoded = key.getEncoded();
        byte[] raw = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "OKP");
        jwk.put("crv", "Ed25519");
        jwk.put("x", Jws.encode(raw));
        return jwk;
    }

    public static PublicKey publicKey(JsonNode jwk) {
        if (!"OKP".equals(jwk.path("kty").asString(null)) || !"Ed25519".equals(jwk.path("crv").asString(null))) {
            throw new IllegalArgumentException("Only Ed25519 OKP keys are supported");
        }
        byte[] raw = Base64.getUrlDecoder().decode(jwk.path("x").asString(""));
        if (raw.length != 32) {
            throw new IllegalArgumentException("An Ed25519 key has 32 bytes");
        }
        byte[] encoded = new byte[ED25519_X509_PREFIX.length + raw.length];
        System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
        System.arraycopy(raw, 0, encoded, ED25519_X509_PREFIX.length, raw.length);
        return SigningKeys.decodePublic(Base64.getEncoder().encodeToString(encoded));
    }
}
