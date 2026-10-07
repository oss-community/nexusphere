package com.nexusphere.ledger.authorization.api.rest;

import com.nexusphere.ledger.authorization.application.MandateService;
import com.nexusphere.ledger.mandate.Jwk;
import com.nexusphere.ledger.server.signing.LedgerSigner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/public/v1")
class PublicController {

    private static final MediaType STATUS_LIST = MediaType.parseMediaType("application/statuslist+jwt");
    private static final MediaType JWK_SET = MediaType.parseMediaType("application/jwk-set+json");

    private final MandateService mandates;
    private final LedgerSigner signer;

    PublicController(MandateService mandates, LedgerSigner signer) {
        this.mandates = mandates;
        this.signer = signer;
    }

    @GetMapping("/keys")
    ResponseEntity<Map<String, Object>> keys() {
        return ResponseEntity.ok().contentType(JWK_SET).cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(Map.of("keys", List.of(Jwk.of(signer.publicKey()))));
    }

    @GetMapping("/mandates/status")
    ResponseEntity<String> status() {
        return ResponseEntity.ok().contentType(STATUS_LIST).cacheControl(CacheControl.noCache())
                .body(mandates.statusList());
    }
}
