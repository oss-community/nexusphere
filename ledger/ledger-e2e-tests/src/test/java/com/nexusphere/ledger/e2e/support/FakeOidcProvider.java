package com.nexusphere.ledger.e2e.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FakeOidcProvider implements AutoCloseable {

    public static final String AUDIENCE = "nexusphere-ledger";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final HttpServer server;
    private final KeyPair keys = rsa();
    private final KeyPair stranger = rsa();

    private FakeOidcProvider(HttpServer server) {
        this.server = server;
    }

    public static FakeOidcProvider start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FakeOidcProvider fake = new FakeOidcProvider(http);
            http.createContext("/.well-known/openid-configuration", fake::configuration);
            http.createContext("/jwks", fake::jwks);
            http.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String issuer() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String token(String username) {
        return token(claims(username), keys.getPrivate());
    }

    public String token(String username, Map<String, Object> overrides) {
        Map<String, Object> claims = claims(username);
        claims.putAll(overrides);
        return token(claims, keys.getPrivate());
    }

    public String forgedToken(String username) {
        return token(claims(username), stranger.getPrivate());
    }

    private Map<String, Object> claims(String username) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", issuer());
        claims.put("sub", "subject-" + username);
        claims.put("aud", List.of(AUDIENCE));
        claims.put("preferred_username", username);
        claims.put("name", username.substring(0, 1).toUpperCase() + username.substring(1));
        claims.put("iat", now);
        claims.put("auth_time", now);
        claims.put("exp", now + 600);
        return claims;
    }

    private static String token(Map<String, Object> claims, PrivateKey key) {
        String header = URL.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", "k1")));
        String payload = URL.encodeToString(JSON.writeValueAsBytes(claims));
        String input = header + "." + payload;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void configuration(HttpExchange exchange) throws IOException {
        send(exchange, Map.of("issuer", issuer(), "jwks_uri", issuer() + "/jwks",
                "id_token_signing_alg_values_supported", List.of("RS256")));
    }

    private void jwks(HttpExchange exchange) throws IOException {
        RSAPublicKey key = (RSAPublicKey) keys.getPublic();
        send(exchange, Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "k1", "use", "sig", "alg", "RS256",
                "n", URL.encodeToString(unsigned(key.getModulus())),
                "e", URL.encodeToString(unsigned(key.getPublicExponent()))))));
    }

    private static void send(HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
