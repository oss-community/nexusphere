package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.SigningKeys;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Jws {

    public static final String ALGORITHM = "EdDSA";

    static final JsonMapper JSON = JsonMapper.builder().build();

    public record Parsed(JsonNode header, JsonNode payload, byte[] signingInput, String signature) {

        public boolean verify(PublicKey key) {
            byte[] raw;
            try {
                raw = Base64.getUrlDecoder().decode(signature);
            } catch (IllegalArgumentException e) {
                return false;
            }
            return SigningKeys.verify(key, signingInput, Base64.getEncoder().encodeToString(raw));
        }
    }

    private Jws() {
    }

    public static String sign(Map<String, ?> header, Map<String, ?> payload, PrivateKey key) {
        String input = encode(JSON.writeValueAsBytes(header)) + "." + encode(JSON.writeValueAsBytes(payload));
        byte[] signature = Base64.getDecoder().decode(SigningKeys.sign(key, input.getBytes(StandardCharsets.US_ASCII)));
        return input + "." + encode(signature);
    }

    public static Parsed parse(String token) {
        if (token == null) {
            throw new IllegalArgumentException("The token is missing");
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("The token is not a compact JWS");
        }
        try {
            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (header == null || !header.isObject() || payload == null || !payload.isObject()) {
                throw new IllegalArgumentException("The token header and payload must be JSON objects");
            }
            return new Parsed(header, payload,
                    (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), parts[2]);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("The token is not valid JSON");
        }
    }

    public static Parsed verified(String token, String type, String issuer, KeyResolver keys) {
        Parsed parsed = parse(token);
        if (!type.equals(parsed.header().path("typ").asString(null))
                || !ALGORITHM.equals(parsed.header().path("alg").asString(null))) {
            throw new IllegalArgumentException("The token is not an EdDSA " + type);
        }
        if (!issuer.equals(parsed.payload().path("iss").asString(null))) {
            throw new IllegalArgumentException("The token was not issued by " + issuer);
        }
        PublicKey key = keys.resolve(issuer, parsed.header().path("kid").asString(""))
                .orElseThrow(() -> new IllegalArgumentException("The token is signed with an unknown key"));
        if (!parsed.verify(key)) {
            throw new IllegalArgumentException("The token signature does not match the issuer's key");
        }
        return parsed;
    }

    public static String sign(String type, String keyId, Map<String, ?> payload, PrivateKey key) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", ALGORITHM);
        header.put("typ", type);
        header.put("kid", keyId);
        return sign(header, payload, key);
    }

    static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
