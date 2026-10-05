package com.nexusphere.identity.contract;

import com.nexusphere.shared.id.IdentityId;

public interface CredentialVerifier {

    boolean verify(IdentityId identityId, String secret);
}
