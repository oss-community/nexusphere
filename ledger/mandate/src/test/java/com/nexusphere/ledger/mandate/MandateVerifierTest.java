package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MandateVerifierTest {

    private static final String ISSUER = "https://ledger.acme.test";
    private static final String STATUS_URI = ISSUER + "/public/v1/mandates/status";
    private static final KeyPair KEYS = SigningKeys.generate();
    private static final KeyPair OTHER = SigningKeys.generate();
    private static final String KID = SigningKeys.keyIdOf(KEYS.getPublic());
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final BitSet revoked = new BitSet();
    private final AtomicInteger fetches = new AtomicInteger();

    private final HttpFetcher http = (uri, accept) -> {
        fetches.incrementAndGet();
        if (uri.equals(URI.create(ISSUER + JwksKeyResolver.KEYS_PATH))) {
            return Jws.JSON.writeValueAsString(Map.of("keys",
                    List.of(Jwk.of(SigningKeys.PublicKeyInfo.of(KEYS.getPublic())))));
        }
        if (uri.equals(URI.create(STATUS_URI))) {
            return new StatusList(ISSUER, STATUS_URI, NOW, NOW.plusSeconds(300), StatusList.DEFAULT_SIZE, revoked)
                    .sign(KID, KEYS.getPrivate());
        }
        throw new IllegalStateException(uri + " answered 404");
    };

    private MandateClaims claims(Instant notBefore, Instant expiresAt, String statusUri) {
        return new MandateClaims(ISSUER, UUID.randomUUID(), "invoice-agent", "alice", "https://supplier.test",
                List.of("a2a/send"), List.of("orders/*"), null, UUID.randomUUID(), "cd".repeat(32), notBefore,
                notBefore, expiresAt, statusUri, 3);
    }

    private String token() {
        return Mandates.issue(claims(NOW.minusSeconds(10), NOW.plusSeconds(3600), STATUS_URI), KID, KEYS.getPrivate());
    }

    private MandateVerifier verifier() {
        return MandateVerifier.builder().trustIssuer(ISSUER).audience("https://supplier.test").http(http).clock(CLOCK)
                .build();
    }

    @Test
    void aGenuineMandateIsValidForWhatItCovers() {
        MandateCheck check = verifier().verify(token(), "a2a/send", "orders/create");

        assertThat(check.problems()).isEmpty();
        assertThat(check.valid()).isTrue();
        assertThat(check.claims().principalId()).isEqualTo("alice");
    }

    @Test
    void anActionOutsideTheMandateIsNotCovered() {
        assertThat(verifier().verify(token(), "a2a/send", "invoices/pay").has(MandateProblem.NOT_COVERED)).isTrue();
    }

    @Test
    void aRevokedMandateIsRejected() {
        revoked.set(3);

        assertThat(verifier().verify(token()).has(MandateProblem.REVOKED)).isTrue();
    }

    @Test
    void anUntrustedIssuerOrForeignKeyIsRejected() {
        String foreign = Mandates.issue(claims(NOW, NOW.plusSeconds(60), STATUS_URI), KID, OTHER.getPrivate());
        String unknownKid = Mandates.issue(claims(NOW, NOW.plusSeconds(60), STATUS_URI), "other", KEYS.getPrivate());
        MandateVerifier strangers = MandateVerifier.builder().trustIssuer("https://ledger.other.test").http(http)
                .clock(CLOCK).build();

        assertThat(verifier().verify(foreign).has(MandateProblem.BAD_SIGNATURE)).isTrue();
        assertThat(verifier().verify(unknownKid).has(MandateProblem.UNKNOWN_KEY)).isTrue();
        assertThat(strangers.verify(token()).has(MandateProblem.UNTRUSTED_ISSUER)).isTrue();
    }

    @Test
    void timeAndAudienceAreChecked() {
        String expired = Mandates.issue(claims(NOW.minusSeconds(7200), NOW.minusSeconds(3600), STATUS_URI), KID,
                KEYS.getPrivate());
        String early = Mandates.issue(claims(NOW.plusSeconds(3600), NOW.plusSeconds(7200), STATUS_URI), KID,
                KEYS.getPrivate());
        MandateVerifier elsewhere = MandateVerifier.builder().trustIssuer(ISSUER).audience("https://bank.test")
                .http(http).clock(CLOCK).build();

        assertThat(verifier().verify(expired).has(MandateProblem.EXPIRED)).isTrue();
        assertThat(verifier().verify(early).has(MandateProblem.NOT_YET_VALID)).isTrue();
        assertThat(elsewhere.verify(token()).has(MandateProblem.WRONG_AUDIENCE)).isTrue();
    }

    @Test
    void aStatusListThatCannotBeTrustedFailsClosed() {
        String elsewhere = Mandates.issue(claims(NOW, NOW.plusSeconds(60), "https://evil.test/status"), KID,
                KEYS.getPrivate());
        HttpFetcher forged = (uri, accept) -> uri.toString().equals(STATUS_URI)
                ? new StatusList(ISSUER, STATUS_URI, NOW, NOW.plusSeconds(300), 8, new BitSet())
                .sign(KID, OTHER.getPrivate())
                : http.get(uri, accept);
        MandateVerifier forgedStatus = MandateVerifier.builder().trustIssuer(ISSUER).http(forged).clock(CLOCK).build();

        assertThat(verifier().verify(elsewhere).has(MandateProblem.STATUS_UNAVAILABLE)).isTrue();
        assertThat(forgedStatus.verify(token()).has(MandateProblem.STATUS_UNAVAILABLE)).isTrue();
    }

    @Test
    void tokensThatAreNotMandatesAreMalformedOrWrongType() {
        String statusList = new StatusList(ISSUER, STATUS_URI, NOW, NOW.plusSeconds(60), 8, new BitSet())
                .sign(KID, KEYS.getPrivate());

        assertThat(verifier().verify("not-a-token").has(MandateProblem.MALFORMED)).isTrue();
        assertThat(verifier().verify(statusList).has(MandateProblem.MALFORMED)).isTrue();
        assertThat(verifier().verify(token()).valid()).isTrue();
    }

    @Test
    void keysAndStatusListsAreCached() {
        MandateVerifier verifier = verifier();
        verifier.verify(token());
        verifier.verify(token());

        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void theStatusCheckCanBeSkippedAndKeysPinned() {
        revoked.set(3);
        MandateVerifier offline = MandateVerifier.builder().trustIssuer(ISSUER)
                .keys(KeyResolver.fixed(ISSUER, KEYS.getPublic())).skipStatus().clock(CLOCK)
                .clockSkew(Duration.ZERO).build();

        assertThat(offline.verify(token()).valid()).isTrue();
        assertThat(fetches.get()).isZero();
        assertThatThrownBy(() -> MandateVerifier.builder().build()).isInstanceOf(IllegalStateException.class);
    }
}
