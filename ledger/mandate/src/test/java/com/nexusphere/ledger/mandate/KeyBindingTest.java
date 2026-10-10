package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyBindingTest {

    private static final String ISSUER = "https://ledger.acme.test";
    private static final String AUDIENCE = "https://supplier.test";
    private static final String STATUS_URI = ISSUER + "/public/v1/mandates/status";
    private static final KeyPair KEYS = SigningKeys.generate();
    private static final KeyPair AGENT = SigningKeys.generate();
    private static final KeyPair THIEF = SigningKeys.generate();
    private static final String KID = SigningKeys.keyIdOf(KEYS.getPublic());
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final String NONCE = "ab".repeat(32);

    private final HttpFetcher http = (uri, accept) -> {
        if (uri.equals(URI.create(ISSUER + JwksKeyResolver.KEYS_PATH))) {
            return Jws.JSON.writeValueAsString(Map.of("keys",
                    List.of(Jwk.of(SigningKeys.PublicKeyInfo.of(KEYS.getPublic())))));
        }
        if (uri.equals(URI.create(STATUS_URI))) {
            return new StatusList(ISSUER, STATUS_URI, NOW, NOW.plusSeconds(300), StatusList.DEFAULT_SIZE, new BitSet())
                    .sign(KID, KEYS.getPrivate());
        }
        throw new IllegalStateException(uri + " answered 404");
    };

    private String mandate(boolean bound) {
        return Mandates.issue(new MandateClaims(ISSUER, UUID.randomUUID(), "invoice-agent", "alice", AUDIENCE,
                List.of("a2a/send"), List.of("orders/*"), null, UUID.randomUUID(), "cd".repeat(32),
                NOW.minusSeconds(10), NOW.minusSeconds(10), NOW.plusSeconds(3600), STATUS_URI, 3,
                bound ? AGENT.getPublic() : null), KID, KEYS.getPrivate());
    }

    private MandateVerifier.Builder builder() {
        return MandateVerifier.builder().trustIssuer(ISSUER).audience(AUDIENCE).http(http)
                .clock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MandateCheck verify(String token, String nonce) {
        return builder().build().verifyBound(token, nonce, "a2a/send", "orders/create");
    }

    @Test
    void theMandateCarriesTheAgentKeyAsConfirmation() {
        MandateClaims claims = Mandates.claims(mandate(true));

        assertThat(claims.bound()).isTrue();
        assertThat(claims.holderKey()).isEqualTo(AGENT.getPublic());
        assertThat(Mandates.claims(mandate(false)).bound()).isFalse();
    }

    @Test
    void aPresentationSignedByTheAgentKeyIsValid() {
        String token = KeyBinding.present(mandate(true), AGENT.getPrivate(), AUDIENCE, NONCE, NOW.minusSeconds(5));

        MandateCheck check = verify(token, NONCE);

        assertThat(check.problems()).isEmpty();
        assertThat(SdJwt.parse(token).keyBinding()).isNotNull();
    }

    @Test
    void aBoundMandateWithoutKeyBindingIsRejected() {
        assertThat(verify(mandate(true), NONCE).has(MandateProblem.KEY_BINDING_MISSING)).isTrue();
    }

    @Test
    void aKeyBindingFromAnotherKeyIsRejected() {
        String token = KeyBinding.present(mandate(true), THIEF.getPrivate(), AUDIENCE, NONCE, NOW);

        assertThat(verify(token, NONCE).has(MandateProblem.KEY_BINDING_INVALID)).isTrue();
    }

    @Test
    void aKeyBindingForAnotherNonceIsRejected() {
        String token = KeyBinding.present(mandate(true), AGENT.getPrivate(), AUDIENCE, NONCE, NOW);

        assertThat(verify(token, "ef".repeat(32)).has(MandateProblem.KEY_BINDING_INVALID)).isTrue();
    }

    @Test
    void aKeyBindingForAnotherAudienceIsRejected() {
        String token = KeyBinding.present(mandate(true), AGENT.getPrivate(), "https://evil.test", NONCE, NOW);

        assertThat(verify(token, NONCE).has(MandateProblem.KEY_BINDING_INVALID)).isTrue();
    }

    @Test
    void aKeyBindingMovedToAnotherPresentationIsRejected() {
        String bound = KeyBinding.present(mandate(true), AGENT.getPrivate(), AUDIENCE, NONCE, NOW);
        String keyBinding = bound.substring(bound.lastIndexOf('~') + 1);

        assertThat(verify(mandate(true) + keyBinding, NONCE).has(MandateProblem.KEY_BINDING_INVALID)).isTrue();
    }

    @Test
    void aStaleKeyBindingIsRejected() {
        String token = KeyBinding.present(mandate(true), AGENT.getPrivate(), AUDIENCE, NONCE, NOW.minusSeconds(600));

        assertThat(verify(token, NONCE).has(MandateProblem.KEY_BINDING_INVALID)).isTrue();
    }

    @Test
    void anUnboundMandateCannotCarryAKeyBinding() {
        String token = KeyBinding.present(mandate(false), AGENT.getPrivate(), AUDIENCE, NONCE, NOW);

        assertThat(verify(token, NONCE).has(MandateProblem.KEY_BINDING_INVALID)).isTrue();
        assertThat(verify(mandate(false), NONCE).problems()).isEmpty();
    }

    @Test
    void aVerifierCanRequireBoundMandates() {
        MandateCheck check = builder().requireKeyBinding().build().verifyBound(mandate(false), NONCE);

        assertThat(check.has(MandateProblem.KEY_NOT_BOUND)).isTrue();
    }

    @Test
    void aPresentationIsBoundOnlyOnce() {
        String token = KeyBinding.present(mandate(true), AGENT.getPrivate(), AUDIENCE, NONCE, NOW);

        assertThatThrownBy(() -> KeyBinding.present(token, AGENT.getPrivate(), AUDIENCE, NONCE, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
