package com.nexusphere.capability.domain.repository;

import com.nexusphere.capability.domain.model.CapabilityType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CapabilityTypeRepository {

    CapabilityType save(CapabilityType type);

    Optional<CapabilityType> findById(UUID id);

    Optional<CapabilityType> find(String code, int version);

    Optional<CapabilityType> findLatest(String code);

    List<CapabilityType> findAll();

    List<CapabilityType> findByCode(String code);
}
