package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultSigningProviderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<KeyPair> versions = new ArrayList<>(List.of(SigningKeys.generate()));
    private final List<String> namespaces = new ArrayList<>();
    private String type = "ed25519";
    private String token = "vault-token";
    private HttpServer server;

    @BeforeEach
    void startVault() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/transit/", this::handle);
        server.start();
    }

    @AfterEach
    void stopVault() {
        server.stop(0);
    }

    private String address() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private LedgerProperties.Signing.Vault settings(String vaultToken, String tokenFile) {
        return new LedgerProperties.Signing.Vault(address() + "/", vaultToken, tokenFile, "team-a", null,
                "ledger key", Duration.ofSeconds(5));
    }

    private static byte[] raw(KeyPair keys) {
        byte[] encoded = keys.getPublic().getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    private void handle(HttpExchange exchange) throws IOException {
        namespaces.add(exchange.getRequestHeaders().getFirst("X-Vault-Namespace"));
        if (!token.equals(exchange.getRequestHeaders().getFirst("X-Vault-Token"))) {
            reply(exchange, 403, Map.of("errors", List.of("permission denied")));
            return;
        }
        String path = exchange.getRequestURI().getRawPath();
        if (path.equals("/v1/transit/keys/ledger%20key")) {
            Map<String, Object> keys = new LinkedHashMap<>();
            for (int index = 0; index < versions.size(); index++) {
                keys.put(String.valueOf(index + 1),
                        Map.of("public_key", Base64.getEncoder().encodeToString(raw(versions.get(index)))));
            }
            reply(exchange, 200, Map.of("data", Map.of("type", type, "latest_version", versions.size(),
                    "keys", keys)));
        } else if (path.equals("/v1/transit/sign/ledger%20key")) {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            int version = body.path("key_version").asInt();
            byte[] input = Base64.getDecoder().decode(body.path("input").asString());
            String signature = SigningKeys.sign(versions.get(version - 1).getPrivate(), input);
            reply(exchange, 200, Map.of("data", Map.of("signature", "vault:v" + version + ":" + signature,
                    "key_version", version)));
        } else {
            reply(exchange, 404, Map.of("errors", List.of()));
        }
    }

    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void signsWithTheLatestVersionAndFindsEarlierVersions() {
        versions.add(SigningKeys.generate());
        VaultSigningProvider provider = new VaultSigningProvider(settings(token, null), JSON);

        assertThat(provider.version()).isEqualTo(2);
        assertThat(provider.publicKey().keyId()).isEqualTo(SigningKeys.keyIdOf(versions.get(1).getPublic()));
        byte[] data = "checkpoint".getBytes(StandardCharsets.UTF_8);
        assertThat(SigningKeys.verify(versions.get(1).getPublic(), data, SigningKeys.sign(provider.signer(), data)))
                .isTrue();

        SigningKeys.PublicKeyInfo first = SigningKeys.PublicKeyInfo.of(versions.get(0).getPublic());
        assertThat(provider.signerFor(first)).hasValueSatisfying(signer -> assertThat(
                SigningKeys.verify(versions.get(0).getPublic(), data, SigningKeys.sign(signer, data))).isTrue());
        assertThat(provider.signerFor(SigningKeys.PublicKeyInfo.of(SigningKeys.generate().getPublic()))).isEmpty();
        assertThat(namespaces).containsOnly("team-a");
    }

    @Test
    void readsTheTokenFromAFileOnEveryCall(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("token");
        Files.writeString(file, "vault-token\n");
        VaultSigningProvider provider = new VaultSigningProvider(settings(null, file.toString()), JSON);

        token = "renewed-token";
        Files.writeString(file, "renewed-token");
        byte[] data = "renewed".getBytes(StandardCharsets.UTF_8);
        assertThat(SigningKeys.verify(versions.get(0).getPublic(), data, SigningKeys.sign(provider.signer(), data)))
                .isTrue();
    }

    @Test
    void refusesWrongSettingsAndKeys() {
        assertThatThrownBy(() -> new VaultSigningProvider(null, JSON))
                .hasMessageContaining("ledger.signing.vault.address");
        assertThatThrownBy(() -> new VaultSigningProvider(settings(null, null), JSON))
                .hasMessageContaining("ledger.signing.vault.token");
        assertThatThrownBy(() -> new VaultSigningProvider(settings("wrong", null), JSON))
                .hasMessageContaining("Vault answered 403").hasMessageNotContaining("wrong");
        type = "aes256-gcm96";
        assertThatThrownBy(() -> new VaultSigningProvider(settings(token, null), JSON))
                .hasMessageContaining("must be of type ed25519");
    }

    @Test
    void reportsAnUnreachableVault() {
        String unreachable = address();
        server.stop(0);
        LedgerProperties.Signing.Vault vault = new LedgerProperties.Signing.Vault(unreachable, token, null, null,
                null, null, Duration.ofSeconds(2));
        assertThatThrownBy(() -> new VaultSigningProvider(vault, JSON)).hasMessageContaining("cannot be reached");
    }
}
