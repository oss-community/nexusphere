package com.nexusphere.identity.contract;

import com.nexusphere.shared.id.IdentityId;

import java.util.Optional;
import java.util.UUID;

public interface CredentialVerifier {

    Optional<UUID> verify(IdentityId identityId, String secret);

    boolean usable(IdentityId identityId, UUID credentialId);
}
