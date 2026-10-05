package com.nexusphere.agreement.contract;

import java.util.Optional;
import java.util.UUID;

public interface AgreementDirectory {

    Optional<AgreementSnapshot> find(UUID agreementId);
}
