package com.nexusphere.audit.infrastructure.persistence;

import com.nexusphere.audit.contract.AuditRecord;
import com.nexusphere.audit.domain.model.AuditQuery;
import com.nexusphere.audit.domain.repository.AuditEventRepository;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaAuditEventRepository implements AuditEventRepository {

    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() {
    };

    private final AuditEventJpaRepository jpa;
    private final JsonMapper json;
    private final TimeProvider time;

    JpaAuditEventRepository(AuditEventJpaRepository jpa, JsonMapper json, TimeProvider time) {
        this.jpa = jpa;
        this.json = json;
        this.time = time;
    }

    @Override
    public void append(AuditRecord record) {
        jpa.saveAndFlush(new AuditEventEntity(record.id(), record.sourceEventId(), record.eventType(),
                record.occurredAt(), time.now(), record.networkId().value(), value(record.principalId()),
                record.identityId() == null ? null : record.identityId().value(),
                record.accountableOrganizationId() == null ? null : record.accountableOrganizationId().value(),
                record.action(), record.resourceType(), record.resourceId(), record.federationId(),
                record.delegationId(), record.agreementId(), record.transactionId(), record.decisionId(),
                record.result(), record.reason(), record.correlationId(), record.causationId(),
                json.writeValueAsString(record.metadata())));
    }

    @Override
    public Optional<AuditRecord> findById(NetworkId networkId, UUID id) {
        return jpa.findByIdAndNetworkId(id, networkId.value()).map(this::toRecord);
    }

    @Override
    public List<AuditRecord> search(NetworkId networkId, AuditQuery query) {
        return jpa.findByNetworkIdOrderByOccurredAtAscRecordedOrderAsc(networkId.value()).stream().map(this::toRecord)
                .filter(record -> matches(query.transactionId(), record.transactionId()))
                .filter(record -> matches(query.agreementId(), record.agreementId()))
                .filter(record -> matches(query.delegationId(), record.delegationId()))
                .filter(record -> matches(query.decisionId(), record.decisionId()))
                .filter(record -> matches(query.principalId(), record.principalId()))
                .filter(record -> matches(query.correlationId(), record.correlationId()))
                .filter(record -> matches(query.result(), record.result()))
                .filter(record -> matches(query.resourceType(), record.resourceType()))
                .filter(record -> matches(query.resourceId(), record.resourceId()))
                .toList();
    }

    private static boolean matches(Object expected, Object actual) {
        return expected == null || Objects.equals(expected, actual);
    }

    private static UUID value(PrincipalId principalId) {
        return principalId == null ? null : principalId.value();
    }

    private AuditRecord toRecord(AuditEventEntity entity) {
        return new AuditRecord(entity.getId(), entity.getSourceEventId(), entity.getEventType(),
                entity.getOccurredAt(), new NetworkId(entity.getNetworkId()),
                entity.getPrincipalId() == null ? null : new PrincipalId(entity.getPrincipalId()),
                entity.getIdentityId() == null ? null : new IdentityId(entity.getIdentityId()),
                entity.getAccountableOrganizationId() == null ? null
                        : new OrganizationId(entity.getAccountableOrganizationId()),
                entity.getAction(), entity.getResourceType(), entity.getResourceId(), entity.getFederationId(),
                entity.getDelegationId(), entity.getAgreementId(), entity.getTransactionId(), entity.getDecisionId(),
                entity.getResult(), entity.getReason(), entity.getCorrelationId(), entity.getCausationId(),
                json.readValue(entity.getMetadata(), METADATA));
    }
}
