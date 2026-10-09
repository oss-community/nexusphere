package com.nexusphere.ledger.mandate;

import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MandateTokenTest {

    private static final KeyPair KEYS = SigningKeys.generate();
    private static final KeyPair OTHER = SigningKeys.generate();
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    static MandateClaims claims() {
        return new MandateClaims("https://ledger.acme.test", UUID.randomUUID(), "invoice-agent", "alice",
                "https://supplier.test", List.of("a2a/send", "tools/call"), List.of("orders/*"), 5L,
                UUID.randomUUID(), "ab".repeat(32), NOW, NOW, NOW.plusSeconds(3600),
                "https://ledger.acme.test/public/v1/mandates/status", 7);
    }

    @Test
    void issuedMandateIsAnSdJwtVcThatRoundTripsAndVerifies() {
        MandateClaims claims = claims();
        String token = Mandates.issue(claims, "kid-1", KEYS.getPrivate());

        SdJwt sdJwt = SdJwt.parse(token);
        Jws.Parsed parsed = sdJwt.jwt();

        assertThat(token).endsWith("~");
        assertThat(parsed.header().path("alg").asString()).isEqualTo("EdDSA");
        assertThat(parsed.header().path("typ").asString()).isEqualTo("dc+sd-jwt");
        assertThat(parsed.header().path("kid").asString()).isEqualTo("kid-1");
        assertThat(parsed.payload().path("vct").asString()).isEqualTo(MandateClaims.VCT);
        assertThat(parsed.payload().path("_sd_alg").asString()).isEqualTo("sha-256");
        assertThat(parsed.payload().toString()).doesNotContain("alice").doesNotContain(claims.termsHash());
        assertThat(parsed.payload().path("mandate").path("_sd").size()).isEqualTo(3);
        assertThat(sdJwt.disclosures()).extracting(SdJwt.Disclosure::name)
                .containsExactlyInAnyOrder("principal", "grant", "termsHash");
        assertThat(parsed.verify(KEYS.getPublic())).isTrue();
        assertThat(parsed.verify(OTHER.getPublic())).isFalse();
        assertThat(Mandates.claims(token)).isEqualTo(claims);
    }

    @Test
    void aHolderCanWithholdSelectiveClaimsButNotTheLimits() {
        MandateClaims claims = claims();
        SdJwt sdJwt = SdJwt.parse(Mandates.issue(claims, "kid-1", KEYS.getPrivate()));

        MandateClaims presented = Mandates.claims(sdJwt.present(java.util.Set.of("grant")));

        assertThat(presented.principalId()).isNull();
        assertThat(presented.termsHash()).isNull();
        assertThat(presented.grantId()).isEqualTo(claims.grantId());
        assertThat(presented.maxUses()).isEqualTo(5L);
        assertThat(presented.actions()).isEqualTo(claims.actions());
    }

    @Test
    void forgedOrForeignDisclosuresAreRejected() {
        String token = Mandates.issue(claims(), "kid-1", KEYS.getPrivate());
        SdJwt sdJwt = SdJwt.parse(token);
        String jwt = token.substring(0, token.indexOf('~'));
        String forged = SdJwt.Disclosure.of("principal", "mallory").encoded();
        String[] parts = jwt.split("\\.");
        String changed = Jws.encode(sdJwt.jwt().payload().toString().replace("invoice-agent", "mallory").getBytes());

        assertThatThrownBy(() -> SdJwt.parse(jwt + "~" + forged + "~").claims())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SdJwt.parse(jwt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SdJwt.parse(token + token.substring(token.indexOf('~') + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Jws.parse(parts[0] + "." + changed + "." + parts[2]).verify(KEYS.getPublic())).isFalse();
    }

    @Test
    void malformedTokensAreRejected() {
        assertThatThrownBy(() -> Jws.parse("a.b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Jws.parse("e30.bm90IGpzb24.c2ln")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Jws.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimsCoverExactAndPrefixPatterns() {
        MandateClaims claims = claims();

        assertThat(claims.covers("a2a/send", "orders/create")).isTrue();
        assertThat(claims.covers("a2a/send", "invoices/create")).isFalse();
        assertThat(claims.covers("a2a/cancel", "orders/create")).isFalse();
    }

    @Test
    void jwkRoundTripsTheEd25519Key() {
        SigningKeys.PublicKeyInfo info = SigningKeys.PublicKeyInfo.of(KEYS.getPublic());

        var jwk = Jws.JSON.valueToTree(Jwk.of(info));

        assertThat(jwk.path("kty").asString()).isEqualTo("OKP");
        assertThat(jwk.path("kid").asString()).isEqualTo(info.keyId());
        assertThat(Jwk.publicKey(jwk).getEncoded()).isEqualTo(KEYS.getPublic().getEncoded());
    }

    @Test
    void statusListRoundTripsRevokedBits() {
        BitSet revoked = new BitSet();
        revoked.set(0);
        revoked.set(7);
        revoked.set(130_000);
        StatusList list = new StatusList("https://ledger.acme.test", "https://ledger.acme.test/status", NOW,
                NOW.plusSeconds(300), StatusList.DEFAULT_SIZE, revoked);

        Jws.Parsed parsed = Jws.parse(list.sign("kid-1", KEYS.getPrivate()));
        StatusList read = StatusList.fromPayload(parsed.payload());

        assertThat(parsed.header().path("typ").asString()).isEqualTo(StatusList.TYPE);
        assertThat(parsed.verify(KEYS.getPublic())).isTrue();
        assertThat(read.isRevoked(0)).isTrue();
        assertThat(read.isRevoked(7)).isTrue();
        assertThat(read.isRevoked(130_000)).isTrue();
        assertThat(read.isRevoked(1)).isFalse();
        assertThat(read.isRevoked(StatusList.DEFAULT_SIZE)).isTrue();
        assertThat(read.expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }
}
