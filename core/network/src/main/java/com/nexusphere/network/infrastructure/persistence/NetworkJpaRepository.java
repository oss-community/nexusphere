package com.nexusphere.network.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface NetworkJpaRepository extends JpaRepository<NetworkEntity, UUID> {

    boolean existsByNameIgnoreCase(String name);
}
