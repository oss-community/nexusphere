package com.nexusphere.ledger.authorization.api.rest;

import com.nexusphere.ledger.authorization.application.MandateService;
import com.nexusphere.ledger.mandate.Jwk;
import com.nexusphere.ledger.server.config.LedgerProperties;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import com.nexusphere.ledger.server.web.LedgerException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/public/v1")
class PublicController {

    private static final MediaType STATUS_LIST = MediaType.parseMediaType("application/statuslist+jwt");
    private static final MediaType JWK_SET = MediaType.parseMediaType("application/jwk-set+json");

    record OidcResponse(String issuer, String clientId) {
    }

    private final MandateService mandates;
    private final LedgerSigner signer;
    private final LedgerProperties properties;

    PublicController(MandateService mandates, LedgerSigner signer, LedgerProperties properties) {
        this.mandates = mandates;
        this.signer = signer;
        this.properties = properties;
    }

    @GetMapping("/oidc")
    OidcResponse oidc() {
        if (!properties.consentRequired()) {
            throw LedgerException.notFound("OIDC sign-in");
        }
        return new OidcResponse(properties.oidc().issuer(), properties.oidc().publicClientId());
    }

    @GetMapping("/keys")
    ResponseEntity<Map<String, Object>> keys() {
        return ResponseEntity.ok().contentType(JWK_SET).cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(Map.of("keys", signer.trustedKeys().stream().map(k -> Jwk.of(k.publicKey())).toList()));
    }

    @GetMapping("/mandates/status")
    ResponseEntity<String> status() {
        return ResponseEntity.ok().contentType(STATUS_LIST).cacheControl(CacheControl.noCache())
                .body(mandates.statusList());
    }
}
