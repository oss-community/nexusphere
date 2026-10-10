package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TrackRecordVerifierTest {

    private static final String ISSUER = "https://ledger.acme.test";
    private static final String AUDIENCE = "https://supplier.test";
    private static final KeyPair KEYS = SigningKeys.generate();
    private static final KeyPair AGENT = SigningKeys.generate();
    private static final KeyPair OTHER = SigningKeys.generate();
    private static final String KID = SigningKeys.keyIdOf(KEYS.getPublic());
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final String NONCE = "ab".repeat(32);

    private final HttpFetcher http = (uri, accept) -> {
        if (uri.equals(URI.create(ISSUER + JwksKeyResolver.KEYS_PATH))) {
            return Jws.JSON.writeValueAsString(Map.of("keys",
                    List.of(Jwk.of(SigningKeys.PublicKeyInfo.of(KEYS.getPublic())))));
        }
        throw new IllegalStateException(uri + " answered 404");
    };

    private TrackRecordClaims claims(String issuer, Instant expiresAt, KeyPair holder) {
        return new TrackRecordClaims(issuer, UUID.randomUUID(), "invoice-agent", AUDIENCE, NOW.minusSeconds(60),
                expiresAt, 42, "cd".repeat(32), "ef".repeat(32), 40, 3L, 42L,
                Instant.parse("2026-09-01T08:00:00Z"), Instant.parse("2026-10-09T17:00:00Z"),
                Map.of("SUCCEEDED", 37L, "DENIED", 3L), 5L, Map.of("tools/call", 30L, "a2a/send", 10L),
                holder == null ? null : holder.getPublic());
    }

    private String issue(TrackRecordClaims claims) {
        return TrackRecords.issue(claims, KID, KEYS.getPrivate());
    }

    private TrackRecordVerifier verifier() {
        return TrackRecordVerifier.builder().trustIssuer(ISSUER).audience(AUDIENCE).http(http)
                .clock(Clock.fixed(NOW, ZoneOffset.UTC)).build();
    }

    @Test
    void anIssuedTrackRecordVerifiesWithEveryClaim() {
        TrackRecordCheck check = verifier().verify(issue(claims(ISSUER, NOW.plusSeconds(3600), null)));

        assertThat(check.problems()).isEmpty();
        assertThat(check.claims().total()).isEqualTo(40);
        assertThat(check.claims().outcomes()).containsEntry("SUCCEEDED", 37L).containsEntry("DENIED", 3L);
        assertThat(check.claims().principals()).isEqualTo(5L);
        assertThat(check.claims().actions()).containsEntry("tools/call", 30L);
        assertThat(check.claims().checkpointSequence()).isEqualTo(42);
    }

    @Test
    void theHolderCanWithholdTheSelectiveClaims() {
        String token = issue(claims(ISSUER, NOW.plusSeconds(3600), null));

        String presented = SdJwt.parse(token).present(Set.of("outcomes"));
        TrackRecordCheck check = verifier().verify(presented);

        assertThat(check.problems()).isEmpty();
        assertThat(check.claims().outcomes()).containsEntry("SUCCEEDED", 37L);
        assertThat(check.claims().principals()).isNull();
        assertThat(check.claims().actions()).isNull();
        assertThat(check.claims().total()).isEqualTo(40);
    }

    @Test
    void theSelectiveClaimsAreNotInTheSignedPayload() {
        String token = issue(claims(ISSUER, NOW.plusSeconds(3600), null));

        String payload = SdJwt.parse(token).jwt().payload().toString();

        assertThat(payload).doesNotContain("SUCCEEDED").doesNotContain("tools/call").contains("_sd");
    }

    @Test
    void anExpiredTrackRecordIsRejected() {
        TrackRecordCheck check = verifier().verify(issue(claims(ISSUER, NOW.minusSeconds(120), null)));

        assertThat(check.has(MandateProblem.EXPIRED)).isTrue();
        assertThat(check.valid()).isFalse();
    }

    @Test
    void anUntrustedIssuerIsRejected() {
        TrackRecordCheck check = verifier().verify(issue(claims("https://evil.test", NOW.plusSeconds(3600), null)));

        assertThat(check.has(MandateProblem.UNTRUSTED_ISSUER)).isTrue();
    }

    @Test
    void aTamperedTotalBreaksTheSignature() {
        String token = issue(claims(ISSUER, NOW.plusSeconds(3600), null));
        String[] parts = token.split("\\.", 3);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]))
                .replace("\"total\":40", "\"total\":400");
        String forged = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes()) + "." + parts[2];

        assertThat(verifier().verify(forged).has(MandateProblem.BAD_SIGNATURE)).isTrue();
    }

    @Test
    void aMandateIsNotATrackRecord() {
        String mandate = Mandates.issue(new MandateClaims(ISSUER, UUID.randomUUID(), "invoice-agent", "alice",
                AUDIENCE, List.of("a2a/send"), List.of("orders/*"), null, UUID.randomUUID(), "cd".repeat(32),
                NOW.minusSeconds(10), NOW.minusSeconds(10), NOW.plusSeconds(3600), null, 0, null), KID,
                KEYS.getPrivate());

        assertThat(verifier().verify(mandate).has(MandateProblem.MALFORMED)).isTrue();
    }

    @Test
    void aBoundTrackRecordNeedsAPresentationByTheAgent() {
        String token = issue(claims(ISSUER, NOW.plusSeconds(3600), AGENT));

        assertThat(verifier().verify(token).has(MandateProblem.KEY_BINDING_MISSING)).isTrue();
        String presented = KeyBinding.present(token, AGENT.getPrivate(), AUDIENCE, NONCE, NOW.minusSeconds(5));
        assertThat(verifier().verifyBound(presented, NONCE).problems()).isEmpty();
        String stolen = KeyBinding.present(token, OTHER.getPrivate(), AUDIENCE, NONCE, NOW.minusSeconds(5));
        assertThat(verifier().verifyBound(stolen, NONCE).has(MandateProblem.KEY_BINDING_INVALID)).isTrue();
    }

    @Test
    void anUnboundTrackRecordFailsWhenKeyBindingIsRequired() {
        TrackRecordVerifier strict = TrackRecordVerifier.builder().trustIssuer(ISSUER).audience(AUDIENCE).http(http)
                .clock(Clock.fixed(NOW, ZoneOffset.UTC)).requireKeyBinding().build();

        assertThat(strict.verify(issue(claims(ISSUER, NOW.plusSeconds(3600), null)))
                .has(MandateProblem.KEY_NOT_BOUND)).isTrue();
    }
}
