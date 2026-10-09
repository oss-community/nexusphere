package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.Hashes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record SdJwt(Jws.Parsed jwt, String issuerJwt, List<Disclosure> disclosures) {

    public static final String HASH_ALGORITHM = "sha-256";

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Disclosure(String encoded, String name, JsonNode value) {

        public static Disclosure of(String name, Object value) {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            String json = Jws.JSON.writeValueAsString(List.of(Jws.encode(salt), name, value));
            return parse(Jws.encode(json.getBytes(StandardCharsets.UTF_8)));
        }

        static Disclosure parse(String encoded) {
            JsonNode array;
            try {
                array = Jws.JSON.readTree(Base64.getUrlDecoder().decode(encoded));
            } catch (JacksonException | IllegalArgumentException e) {
                throw new IllegalArgumentException("A disclosure is not base64url JSON");
            }
            if (array == null || !array.isArray() || array.size() != 3 || !array.get(0).isString()
                    || !array.get(1).isString() || array.get(1).asString().equals("_sd")
                    || array.get(1).asString().equals("...")) {
                throw new IllegalArgumentException("A disclosure is not a [salt, name, value] array");
            }
            return new Disclosure(encoded, array.get(1).asString(), array.get(2));
        }

        public String digest() {
            return Jws.encode(HexFormat.of().parseHex(Hashes.sha256(encoded.getBytes(StandardCharsets.US_ASCII))));
        }
    }

    public static String issue(Map<String, ?> header, Map<String, ?> payload, List<Disclosure> disclosures,
                               PrivateKey key) {
        StringBuilder token = new StringBuilder(Jws.sign(header, payload, key)).append('~');
        disclosures.forEach(disclosure -> token.append(disclosure.encoded()).append('~'));
        return token.toString();
    }

    public static List<String> digests(List<Disclosure> disclosures) {
        return disclosures.stream().map(Disclosure::digest).sorted().toList();
    }

    public static SdJwt parse(String token) {
        if (token == null) {
            throw new IllegalArgumentException("The token is missing");
        }
        String trimmed = token.trim();
        if (!trimmed.endsWith("~")) {
            throw new IllegalArgumentException("The token is not an SD-JWT without key binding");
        }
        String[] parts = trimmed.split("~", -1);
        List<Disclosure> disclosures = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < parts.length - 1; i++) {
            if (!seen.add(parts[i])) {
                throw new IllegalArgumentException("A disclosure is repeated");
            }
            disclosures.add(Disclosure.parse(parts[i]));
        }
        return new SdJwt(Jws.parse(parts[0]), parts[0], List.copyOf(disclosures));
    }

    public JsonNode claims() {
        JsonNode payload = jwt.payload();
        if (payload.has("_sd_alg") && !HASH_ALGORITHM.equals(payload.path("_sd_alg").asString(null))) {
            throw new IllegalArgumentException("The token hashes its disclosures with an unsupported algorithm");
        }
        Map<String, Disclosure> byDigest = new HashMap<>();
        disclosures.forEach(disclosure -> byDigest.put(disclosure.digest(), disclosure));
        ObjectNode claims = (ObjectNode) payload.deepCopy();
        claims.remove("_sd_alg");
        Set<String> used = new HashSet<>();
        reveal(claims, byDigest, used);
        if (used.size() != disclosures.size()) {
            throw new IllegalArgumentException("A disclosure is not referenced by the token");
        }
        return claims;
    }

    public String present(Set<String> names) {
        StringBuilder token = new StringBuilder(issuerJwt).append('~');
        disclosures.stream().filter(disclosure -> names.contains(disclosure.name()))
                .forEach(disclosure -> token.append(disclosure.encoded()).append('~'));
        return token.toString();
    }

    private static void reveal(JsonNode node, Map<String, Disclosure> byDigest, Set<String> used) {
        if (node instanceof ObjectNode object) {
            JsonNode digests = object.remove("_sd");
            List<String> names = new ArrayList<>(object.propertyNames());
            names.forEach(name -> reveal(object.get(name), byDigest, used));
            if (digests == null) {
                return;
            }
            if (!(digests instanceof ArrayNode array)) {
                throw new IllegalArgumentException("The token has a malformed _sd claim");
            }
            for (JsonNode digest : array) {
                Disclosure disclosure = byDigest.get(digest.asString());
                if (disclosure == null) {
                    continue;
                }
                if (!used.add(digest.asString()) || object.has(disclosure.name())) {
                    throw new IllegalArgumentException("The disclosed claim " + disclosure.name() + " is repeated");
                }
                object.set(disclosure.name(), disclosure.value());
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(item -> reveal(item, byDigest, used));
        }
    }
}
