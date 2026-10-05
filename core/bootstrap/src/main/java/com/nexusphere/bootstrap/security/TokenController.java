package com.nexusphere.bootstrap.security;

import com.nexusphere.identity.contract.CredentialVerifier;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.id.IdentityId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/token")
class TokenController {

    record TokenRequest(@NotBlank String identityId, @NotBlank String secret) {
    }

    private final CredentialVerifier credentials;
    private final LocalTokenIssuer issuer;

    TokenController(CredentialVerifier credentials, LocalTokenIssuer issuer) {
        this.credentials = credentials;
        this.issuer = issuer;
    }

    @PostMapping
    LocalTokenIssuer.IssuedToken token(@Valid @RequestBody TokenRequest request) {
        IdentityId identityId = IdentityId.of(request.identityId());
        if (!credentials.verify(identityId, request.secret())) {
            throw new DomainException(ErrorCategory.AUTHENTICATION_ERROR, "INVALID_CREDENTIALS",
                    "The identity or secret is not valid");
        }
        return issuer.issue(identityId);
    }
}
