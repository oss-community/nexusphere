package com.nexusphere.audit.contract;

import com.nexusphere.shared.id.NetworkId;

import java.util.List;
import java.util.UUID;

public interface AuditTrail {

    List<AuditRecord> findByTransaction(NetworkId networkId, UUID transactionId);
}
