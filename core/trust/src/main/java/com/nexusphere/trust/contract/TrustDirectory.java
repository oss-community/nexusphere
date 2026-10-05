package com.nexusphere.trust.contract;

import java.util.Optional;

public interface TrustDirectory {

    Optional<TrustSnapshot> findEffective(PartyReference source, PartyReference target, String scope);
}
