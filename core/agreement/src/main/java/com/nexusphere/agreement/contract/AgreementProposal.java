package com.nexusphere.agreement.contract;

import com.nexusphere.shared.id.CapabilityId;

import java.util.Map;
import java.util.UUID;

public record AgreementProposal(CapabilityId capabilityId, String type, String title, Map<String, Object> terms,
                                UUID delegationId) {
}
