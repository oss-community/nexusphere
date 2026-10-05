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
@RequestMapping("/api/v1/auth")
class TokenController {

    record TokenRequest(@NotBlank String identityId, @NotBlank String secret) {
    }

    record OperatorTokenRequest(@NotBlank String secret) {
    }

    private final CredentialVerifier credentials;
    private final LocalTokenIssuer issuer;
    private final OperatorProperties operator;

    TokenController(CredentialVerifier credentials, LocalTokenIssuer issuer, OperatorProperties operator) {
        this.credentials = credentials;
        this.issuer = issuer;
        this.operator = operator;
    }

    @PostMapping("/operator-token")
    LocalTokenIssuer.IssuedToken operatorToken(@Valid @RequestBody OperatorTokenRequest request) {
        if (!operator.matches(request.secret())) {
            throw new DomainException(ErrorCategory.AUTHENTICATION_ERROR, "INVALID_CREDENTIALS",
                    "The operator secret is not valid");
        }
        return issuer.issueOperator();
    }

    @PostMapping("/token")
    LocalTokenIssuer.IssuedToken token(@Valid @RequestBody TokenRequest request) {
        IdentityId identityId = IdentityId.of(request.identityId());
        if (!credentials.verify(identityId, request.secret())) {
            throw new DomainException(ErrorCategory.AUTHENTICATION_ERROR, "INVALID_CREDENTIALS",
                    "The identity or secret is not valid");
        }
        return issuer.issue(identityId);
    }
}
