package com.nexusphere.agreement.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface AgreementVersionJpaRepository extends JpaRepository<AgreementVersionEntity, UUID> {

    List<AgreementVersionEntity> findByAgreementIdOrderByNumber(UUID agreementId);
}
