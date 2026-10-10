package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.Signer;
import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSignaturesTest {

    private static final KeyPair KEYS = SigningKeys.generate();
    private static final String KID = SigningKeys.keyIdOf(KEYS.getPublic());
    private static final URI URL = URI.create("https://ledger.supplier.test/a2a/in/sales");
    private static final byte[] BODY = "{\"jsonrpc\":\"2.0\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final Duration SKEW = Duration.ofSeconds(60);
    private static final HttpSignatures.Keys RESOLVER = keyId -> KID.equals(keyId) ? Optional.of(KEYS.getPublic())
            : Optional.empty();

    private static Map<String, String> signed(byte[] body) {
        Map<String, String> headers = new HashMap<>();
        HttpSignatures.sign("POST", URL, body, KID, Signer.of(KEYS.getPrivate()), NOW, "n-1")
                .forEach((name, value) -> headers.put(name.toLowerCase(), value));
        return headers;
    }

    private static HttpSignatures.Verified verify(String method, URI uri, Map<String, String> headers, byte[] body,
                                                  Instant now) {
        return HttpSignatures.verify(method, uri, name -> headers.get(name.toLowerCase()), body, RESOLVER, now, SKEW);
    }

    @Test
    void aSignedRequestVerifiesWithTheKeyAndNonce() {
        Map<String, String> headers = signed(BODY);

        HttpSignatures.Verified verified = verify("POST", URL, headers, BODY, NOW.plusSeconds(30));

        assertThat(verified.keyId()).isEqualTo(KID);
        assertThat(verified.nonce()).isEqualTo("n-1");
        assertThat(headers.get("signature-input")).startsWith("nexusphere=(\"@method\" \"@authority\" \"@path\" "
                + "\"content-digest\");created=" + NOW.getEpochSecond());
        assertThat(headers.get("content-digest")).isEqualTo(HttpSignatures.contentDigest(BODY));
    }

    @Test
    void aRequestWithoutBodyIsSignedWithoutDigest() {
        Map<String, String> headers = signed(null);

        assertThat(headers).doesNotContainKey("content-digest");
        assertThat(verify("POST", URL, headers, null, NOW).keyId()).isEqualTo(KID);
    }

    @Test
    void aChangedBodyMethodOrAddressIsRejected() {
        Map<String, String> headers = signed(BODY);

        assertThatThrownBy(() -> verify("POST", URL, headers, "{}".getBytes(StandardCharsets.UTF_8), NOW))
                .isInstanceOf(HttpSignatures.InvalidSignature.class).hasMessageContaining("Content-Digest");
        assertThatThrownBy(() -> verify("PUT", URL, headers, BODY, NOW))
                .isInstanceOf(HttpSignatures.InvalidSignature.class);
        assertThatThrownBy(() -> verify("POST", URI.create("https://ledger.supplier.test/a2a/in/billing"), headers,
                BODY, NOW)).isInstanceOf(HttpSignatures.InvalidSignature.class);
        assertThatThrownBy(() -> verify("POST", URI.create("https://evil.test/a2a/in/sales"), headers, BODY, NOW))
                .isInstanceOf(HttpSignatures.InvalidSignature.class);
    }

    @Test
    void aStaleOrUnknownOrMissingSignatureIsRejected() {
        Map<String, String> headers = signed(BODY);
        Map<String, String> unknown = new HashMap<>(headers);
        unknown.put("signature-input", headers.get("signature-input").replace(KID, "0000000000000000"));

        assertThatThrownBy(() -> verify("POST", URL, headers, BODY, NOW.plusSeconds(600)))
                .hasMessageContaining("not valid now");
        assertThatThrownBy(() -> verify("POST", URL, unknown, BODY, NOW)).hasMessageContaining("unknown");
        assertThatThrownBy(() -> verify("POST", URL, Map.of(), BODY, NOW)).hasMessageContaining("Signature-Input");
    }

    @Test
    void aSignatureThatLeavesOutTheDigestIsRejected() {
        Map<String, String> headers = signed(BODY);
        headers.put("signature-input", headers.get("signature-input").replace(" \"content-digest\"", ""));

        assertThatThrownBy(() -> verify("POST", URL, headers, BODY, NOW)).hasMessageContaining("must cover");
    }

    @Test
    void theDefaultPortIsLeftOutOfTheAuthority() {
        assertThat(HttpSignatures.authority(URI.create("https://Ledger.Test:443/x"))).isEqualTo("ledger.test");
        assertThat(HttpSignatures.authority(URI.create("http://localhost:8090/x"))).isEqualTo("localhost:8090");
    }
}
