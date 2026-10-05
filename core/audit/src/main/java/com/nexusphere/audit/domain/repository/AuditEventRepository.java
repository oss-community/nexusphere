package com.nexusphere.audit.domain.repository;

import com.nexusphere.audit.contract.AuditRecord;
import com.nexusphere.audit.domain.model.AuditQuery;
import com.nexusphere.shared.id.NetworkId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository {

    void append(AuditRecord record);

    Optional<AuditRecord> findById(NetworkId networkId, UUID id);

    List<AuditRecord> search(NetworkId networkId, AuditQuery query);
}
