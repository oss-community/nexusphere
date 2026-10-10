package com.nexusphere.ledger.a2a;

import com.nexusphere.ledger.authorization.application.MandateService;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.mandate.HttpFetcher;
import com.nexusphere.ledger.mandate.JwksKeyResolver;
import com.nexusphere.ledger.mandate.KeyResolver;
import com.nexusphere.ledger.mandate.MandateVerifier;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import org.springframework.stereotype.Component;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
class A2aKeys implements KeyResolver {

    private static final Duration CACHE = Duration.ofMinutes(5);

    private final LedgerSigner signer;
    private final MandateService mandates;
    private final HttpFetcher http;
    private final JwksKeyResolver jwks;
    private final MandateVerifier verifier;
    private final Clock clock;
    private volatile MandateVerifier own;

    A2aKeys(LedgerSigner signer, MandateService mandates, LedgerProperties properties, Clock clock) {
        this.signer = signer;
        this.mandates = mandates;
        this.http = HttpFetcher.create(Duration.ofSeconds(10));
        this.jwks = new JwksKeyResolver(http, clock, CACHE);
        this.clock = clock;
        List<String> trusted = properties.a2a() == null || properties.a2a().trustedIssuers() == null ? List.of()
                : properties.a2a().trustedIssuers().stream().filter(i -> !i.isBlank()).toList();
        if (trusted.isEmpty()) {
            this.verifier = null;
        } else {
            MandateVerifier.Builder builder = MandateVerifier.builder().keys(this).http(http).clock(clock)
                    .cacheTtl(properties.a2a().statusListCache() == null ? Duration.ofSeconds(30)
                            : properties.a2a().statusListCache()).audience(mandates.issuer());
            trusted.forEach(builder::trustIssuer);
            this.verifier = builder.build();
        }
    }

    @Override
    public Optional<PublicKey> resolve(String issuer, String keyId) {
        if (mandates.issuer().equals(issuer)) {
            return signer.findTrusted(keyId).map(SigningKeys.PublicKeyInfo::publicKey);
        }
        return jwks.resolve(issuer, keyId);
    }

    MandateVerifier own() {
        MandateVerifier verifier = own;
        if (verifier == null) {
            verifier = MandateVerifier.builder().keys(this).clock(clock).trustIssuer(mandates.issuer())
                    .statusLists((issuer, uri) -> mandates.currentStatusList()).requireKeyBinding().build();
            own = verifier;
        }
        return verifier;
    }

    Optional<MandateVerifier> verifier() {
        return Optional.ofNullable(verifier);
    }
}
