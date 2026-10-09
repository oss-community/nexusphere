package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.server.config.LedgerProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

final class VaultSigningProvider implements SigningProvider {

    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final JsonMapper json;
    private final String address;
    private final String token;
    private final Path tokenFile;
    private final String namespace;
    private final String keyPath;
    private final String signPath;
    private final Duration timeout;
    private final Map<Integer, SigningKeys.PublicKeyInfo> versions;
    private final int version;
    private final Signer signer;

    VaultSigningProvider(LedgerProperties.Signing.Vault vault, JsonMapper json) {
        if (vault == null || isBlank(vault.address())) {
            throw new IllegalStateException("ledger.signing.vault.address must be set for the vault provider");
        }
        if (isBlank(vault.token()) && isBlank(vault.tokenFile())) {
            throw new IllegalStateException("ledger.signing.vault.token or ledger.signing.vault.token-file must be set");
        }
        this.json = json;
        this.address = vault.address().replaceAll("/+$", "");
        this.token = isBlank(vault.token()) ? null : vault.token().trim();
        this.tokenFile = isBlank(vault.tokenFile()) ? null : Path.of(vault.tokenFile().trim());
        this.namespace = isBlank(vault.namespace()) ? null : vault.namespace().trim();
        this.timeout = vault.timeout() == null ? DEFAULT_TIMEOUT : vault.timeout();
        String mount = segment(isBlank(vault.mount()) ? "transit" : vault.mount().trim());
        String key = segment(isBlank(vault.key()) ? "nexusphere-ledger" : vault.key().trim());
        this.keyPath = "/v1/" + mount + "/keys/" + key;
        this.signPath = "/v1/" + mount + "/sign/" + key;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JsonNode data = call("GET", keyPath, null).path("data");
        if (!"ed25519".equals(data.path("type").asString(""))) {
            throw new IllegalStateException("The Vault transit key " + key + " must be of type ed25519");
        }
        this.versions = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : data.path("keys").properties()) {
            byte[] raw = Base64.getDecoder().decode(entry.getValue().path("public_key").asString(""));
            versions.put(Integer.parseInt(entry.getKey()), publicKeyOf(raw));
        }
        this.version = data.path("latest_version").asInt(0);
        if (!versions.containsKey(version)) {
            throw new IllegalStateException("The Vault transit key " + key + " has no public key for version "
                    + version);
        }
        this.signer = signer(version);
        if (!SigningKeys.matches(signer, publicKey().publicKey())) {
            throw new IllegalStateException("Vault signed with a key that does not match the public key of version "
                    + version + " of " + key);
        }
    }

    static SigningKeys.PublicKeyInfo publicKeyOf(byte[] raw) {
        if (raw.length != 32) {
            throw new IllegalStateException("An Ed25519 public key has 32 bytes, not " + raw.length);
        }
        byte[] spki = new byte[SPKI_PREFIX.length + raw.length];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, spki, SPKI_PREFIX.length, raw.length);
        return SigningKeys.PublicKeyInfo.of(SigningKeys.decodePublic(Base64.getEncoder().encodeToString(spki)));
    }

    @Override
    public String name() {
        return "vault";
    }

    @Override
    public SigningKeys.PublicKeyInfo publicKey() {
        return versions.get(version);
    }

    @Override
    public Signer signer() {
        return signer;
    }

    @Override
    public Optional<Signer> signerFor(SigningKeys.PublicKeyInfo previous) {
        return versions.entrySet().stream()
                .filter(entry -> entry.getValue().keyId().equals(previous.keyId()))
                .map(entry -> signer(entry.getKey()))
                .findFirst();
    }

    int version() {
        return version;
    }

    private Signer signer(int keyVersion) {
        return data -> {
            Map<String, Object> body = Map.of("input", Base64.getEncoder().encodeToString(data),
                    "key_version", keyVersion);
            String signature = call("POST", signPath, body).path("data").path("signature").asString("");
            String prefix = "vault:v" + keyVersion + ":";
            if (!signature.startsWith(prefix)) {
                throw new IllegalStateException("Vault did not sign with version " + keyVersion);
            }
            return Base64.getDecoder().decode(signature.substring(prefix.length()));
        };
    }

    private JsonNode call(String method, String path, Map<String, Object> body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(address + path))
                .timeout(timeout)
                .header("X-Vault-Token", token())
                .header("Accept", "application/json");
        if (namespace != null) {
            request.header("X-Vault-Namespace", namespace);
        }
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Vault cannot be reached at " + address + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Vault", e);
        }
        JsonNode answer = response.body().length == 0 ? json.createObjectNode() : json.readTree(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Vault answered " + response.statusCode() + " to " + method + " "
                    + path + ": " + answer.path("errors"));
        }
        return answer;
    }

    private String token() {
        if (tokenFile == null) {
            return token;
        }
        try {
            return Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("The Vault token file " + tokenFile + " cannot be read", e);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
