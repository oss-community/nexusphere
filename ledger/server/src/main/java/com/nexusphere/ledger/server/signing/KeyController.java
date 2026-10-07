package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.chain.SigningKeys;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/keys")
class KeyController {

    record KeyResponse(String keyId, String algorithm, String publicKey) {
    }

    private final LedgerSigner signer;

    KeyController(LedgerSigner signer) {
        this.signer = signer;
    }

    @GetMapping
    List<KeyResponse> keys() {
        SigningKeys.PublicKeyInfo key = signer.publicKey();
        return List.of(new KeyResponse(key.keyId(), SigningKeys.ALGORITHM, key.encoded()));
    }
}
