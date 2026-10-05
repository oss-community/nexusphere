package com.nexusphere.transaction.contract;

import com.nexusphere.shared.id.CapabilityId;

import java.util.Map;
import java.util.UUID;

public record TransactionRequest(UUID agreementId, CapabilityId capabilityId, String type,
                                 Map<String, Object> metadata, UUID delegationId) {
}
