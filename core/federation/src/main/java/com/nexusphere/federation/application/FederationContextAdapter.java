package com.nexusphere.federation.application;

import com.nexusphere.authorization.contract.FederationContext;
import com.nexusphere.authorization.contract.FederationContextPort;
import com.nexusphere.federation.domain.repository.FederationRepository;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
class FederationContextAdapter implements FederationContextPort {

    private final FederationRepository federations;
    private final TimeProvider time;

    FederationContextAdapter(FederationRepository federations, TimeProvider time) {
        this.federations = federations;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FederationContext> findActive(NetworkId first, NetworkId second) {
        Instant now = time.now();
        return federations.findBetween(first, second).stream().filter(federation -> federation.isActive(now))
                .findFirst()
                .map(federation -> new FederationContext(federation.id(),
                        federation.scopes().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet())));
    }
}
