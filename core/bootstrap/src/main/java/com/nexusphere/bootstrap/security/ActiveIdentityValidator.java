package com.nexusphere.bootstrap.security;

import com.nexusphere.identity.contract.IdentityDirectory;
import com.nexusphere.identity.contract.IdentitySnapshot;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.id.IdentityId;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class ActiveIdentityValidator implements OAuth2TokenValidator<Jwt> {

    private final IdentityDirectory identities;

    ActiveIdentityValidator(IdentityDirectory identities) {
        this.identities = identities;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        IdentityId identityId;
        try {
            identityId = IdentityId.of(token.getSubject());
        } catch (DomainException | NullPointerException e) {
            return failure("The token subject is not an identity");
        }
        boolean active = identities.find(identityId).map(IdentitySnapshot::active).orElse(false);
        return active ? OAuth2TokenValidatorResult.success() : failure("Identity " + identityId + " is not active");
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
