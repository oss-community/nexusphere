package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.chain.SigningKeys;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class HttpSignatures {

    public static final String LABEL = "nexusphere";
    public static final String TAG = "nexusphere-ledger";
    public static final String ALGORITHM = "ed25519";
    public static final String SIGNATURE_INPUT = "Signature-Input";
    public static final String SIGNATURE = "Signature";
    public static final String CONTENT_DIGEST = "Content-Digest";
    public static final Duration VALIDITY = Duration.ofMinutes(5);

    private HttpSignatures() {
    }

    public interface Keys {

        Optional<PublicKey> resolve(String keyId);
    }

    public record Verified(String keyId, Instant created, String nonce) {
    }

    public static final class InvalidSignature extends RuntimeException {

        public InvalidSignature(String message) {
            super(message);
        }
    }

    public static Map<String, String> sign(String method, URI uri, byte[] body, String keyId, Signer signer,
                                           Instant created, String nonce) {
        List<String> components = components(body);
        Map<String, String> headers = new LinkedHashMap<>();
        if (components.contains("content-digest")) {
            headers.put(CONTENT_DIGEST, contentDigest(body));
        }
        long createdAt = created.getEpochSecond();
        String params = innerList(components) + ";created=" + createdAt + ";expires="
                + (createdAt + VALIDITY.toSeconds()) + ";nonce=" + quote(nonce) + ";keyid=" + quote(keyId)
                + ";alg=" + quote(ALGORITHM) + ";tag=" + quote(TAG);
        String digest = headers.get(CONTENT_DIGEST);
        String base = signatureBase(method, uri, components,
                name -> CONTENT_DIGEST.equalsIgnoreCase(name) ? digest : null, params);
        headers.put(SIGNATURE_INPUT, LABEL + "=" + params);
        headers.put(SIGNATURE, LABEL + "=:" + Base64.getEncoder().encodeToString(
                signer.sign(base.getBytes(StandardCharsets.UTF_8))) + ":");
        return headers;
    }

    public static Verified verify(String method, URI uri, Function<String, String> header, byte[] body, Keys keys,
                                  Instant now, Duration clockSkew) {
        String input = member(header.apply(SIGNATURE_INPUT), "The request has no " + SIGNATURE_INPUT + " "
                + LABEL);
        String signature = member(header.apply(SIGNATURE), "The request has no " + SIGNATURE + " " + LABEL);
        Params params = Params.parse(input);
        List<String> required = components(body);
        if (!params.components().containsAll(required)) {
            throw new InvalidSignature("The signature must cover " + String.join(", ", required));
        }
        if (params.text("alg") != null && !ALGORITHM.equals(params.text("alg"))) {
            throw new InvalidSignature("The signature algorithm must be " + ALGORITHM);
        }
        if (!TAG.equals(params.text("tag"))) {
            throw new InvalidSignature("The signature tag must be " + TAG);
        }
        Long created = params.number("created");
        Long expires = params.number("expires");
        if (created == null || expires == null) {
            throw new InvalidSignature("The signature needs created and expires");
        }
        Instant createdAt = Instant.ofEpochSecond(created);
        if (createdAt.isAfter(now.plus(clockSkew)) || Instant.ofEpochSecond(expires).isBefore(now.minus(clockSkew))
                || expires - created > VALIDITY.toSeconds()) {
            throw new InvalidSignature("The signature was made at " + createdAt + " and is not valid now");
        }
        String keyId = params.text("keyid");
        if (keyId == null) {
            throw new InvalidSignature("The signature has no keyid");
        }
        if (params.components().contains("content-digest")) {
            String digest = header.apply(CONTENT_DIGEST);
            if (digest == null || !digest.trim().equals(contentDigest(body == null ? new byte[0] : body))) {
                throw new InvalidSignature("The Content-Digest does not match the body");
            }
        }
        PublicKey key = keys.resolve(keyId).orElseThrow(() -> new InvalidSignature("The key " + keyId
                + " is unknown"));
        if (!signature.startsWith(":") || !signature.endsWith(":") || signature.length() < 2) {
            throw new InvalidSignature("The signature is not a byte sequence");
        }
        String base = signatureBase(method, uri, params.components(), header, input);
        if (!SigningKeys.verify(key, base.getBytes(StandardCharsets.UTF_8),
                signature.substring(1, signature.length() - 1))) {
            throw new InvalidSignature("The signature does not match the request");
        }
        return new Verified(keyId, createdAt, params.text("nonce"));
    }

    public static String contentDigest(byte[] body) {
        try {
            return "sha-256=:" + Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(body)) + ":";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String signatureBase(String method, URI uri, List<String> components,
                                       Function<String, String> header, String params) {
        StringBuilder base = new StringBuilder();
        for (String component : components) {
            base.append('"').append(component).append("\": ").append(value(component, method, uri, header))
                    .append('\n');
        }
        return base.append("\"@signature-params\": ").append(params).toString();
    }

    public static String authority(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean standard = port == -1 || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
        return standard ? host : host + ":" + port;
    }

    private static List<String> components(byte[] body) {
        return body == null || body.length == 0 ? List.of("@method", "@authority", "@path")
                : List.of("@method", "@authority", "@path", "content-digest");
    }

    private static String value(String component, String method, URI uri, Function<String, String> header) {
        return switch (component) {
            case "@method" -> method.toUpperCase(Locale.ROOT);
            case "@authority" -> authority(uri);
            case "@path" -> uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            case "@query" -> "?" + (uri.getRawQuery() == null ? "" : uri.getRawQuery());
            default -> {
                if (component.startsWith("@")) {
                    throw new InvalidSignature("The component " + component + " is not supported");
                }
                String value = header.apply(component);
                if (value == null) {
                    throw new InvalidSignature("The request has no " + component + " header");
                }
                yield value.trim();
            }
        };
    }

    private static String innerList(List<String> components) {
        List<String> quoted = components.stream().map(HttpSignatures::quote).toList();
        return "(" + String.join(" ", quoted) + ")";
    }

    private static String quote(String text) {
        if (text.chars().anyMatch(c -> c < 0x20 || c > 0x7e || c == '"' || c == '\\')) {
            throw new IllegalArgumentException("Not a structured field string: " + text);
        }
        return '"' + text + '"';
    }

    private static String member(String dictionary, String missing) {
        if (dictionary == null) {
            throw new InvalidSignature(missing);
        }
        for (String member : split(dictionary)) {
            int equals = member.indexOf('=');
            if (equals > 0 && member.substring(0, equals).trim().equals(LABEL)) {
                return member.substring(equals + 1).trim();
            }
        }
        throw new InvalidSignature(missing);
    }

    private static List<String> split(String dictionary) {
        List<String> members = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < dictionary.length(); i++) {
            char c = dictionary.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                members.add(dictionary.substring(start, i));
                start = i + 1;
            }
        }
        members.add(dictionary.substring(start));
        return members;
    }

    private record Params(List<String> components, Map<String, String> values) {

        static Params parse(String text) {
            if (!text.startsWith("(")) {
                throw new InvalidSignature("The signature input is not an inner list");
            }
            int close = text.indexOf(')');
            if (close < 0) {
                throw new InvalidSignature("The signature input is not an inner list");
            }
            List<String> components = new ArrayList<>();
            for (String item : text.substring(1, close).trim().split(" +")) {
                if (item.isEmpty()) {
                    continue;
                }
                if (item.length() < 2 || !item.startsWith("\"") || !item.endsWith("\"")) {
                    throw new InvalidSignature("A covered component is not a string");
                }
                components.add(item.substring(1, item.length() - 1));
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (String param : text.substring(close + 1).split(";")) {
                if (param.isBlank()) {
                    continue;
                }
                int equals = param.indexOf('=');
                if (equals < 0) {
                    throw new InvalidSignature("A signature parameter has no value");
                }
                values.put(param.substring(0, equals).trim(), param.substring(equals + 1).trim());
            }
            return new Params(List.copyOf(components), values);
        }

        String text(String name) {
            String value = values.get(name);
            return value == null || value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"") ? null
                    : value.substring(1, value.length() - 1);
        }

        Long number(String name) {
            try {
                return values.containsKey(name) ? Long.parseLong(values.get(name)) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
