package com.nexusphere.audit.api.rest;

import com.nexusphere.audit.application.AuditService;
import com.nexusphere.audit.contract.AuditRecord;
import com.nexusphere.audit.domain.model.AuditQuery;
import com.nexusphere.audit.domain.model.TrailLink;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.shared.id.PrincipalId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-events")
class AuditController {

    record AuditEventResponse(String id, String eventType, Instant occurredAt, String networkId, String principalId,
                              String identityId, String accountableOrganizationId, String action,
                              String resourceType, String resourceId, String federationId, String delegationId,
                              String agreementId, String transactionId, String decisionId, String result,
                              String reason, String correlationId, String causationId, Map<String, String> metadata) {

        static AuditEventResponse of(AuditRecord record) {
            return new AuditEventResponse(record.id().toString(), record.eventType(), record.occurredAt(),
                    text(record.networkId()), text(record.principalId()), text(record.identityId()),
                    text(record.accountableOrganizationId()), record.action(), record.resourceType(),
                    record.resourceId(), text(record.federationId()), text(record.delegationId()),
                    text(record.agreementId()), text(record.transactionId()), text(record.decisionId()),
                    record.result(), record.reason(), record.correlationId(), text(record.causationId()),
                    record.metadata());
        }
    }

    record TrailResponse(String transactionId, List<TrailLink> chain, List<AuditEventResponse> events) {
    }

    private final AuditService audit;

    AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    List<AuditEventResponse> search(PrincipalContext principal, ExecutionContext context,
                                    @RequestParam(required = false) String transactionId,
                                    @RequestParam(required = false) String agreementId,
                                    @RequestParam(required = false) String delegationId,
                                    @RequestParam(required = false) String decisionId,
                                    @RequestParam(required = false) String principalId,
                                    @RequestParam(required = false) String correlationId,
                                    @RequestParam(required = false) String result,
                                    @RequestParam(required = false) String resourceType,
                                    @RequestParam(required = false) String resourceId) {
        AuditQuery query = new AuditQuery(uuid(transactionId), uuid(agreementId), uuid(delegationId),
                uuid(decisionId), principalId == null ? null : PrincipalId.of(principalId), correlationId, result,
                resourceType, resourceId);
        return audit.search(principal, query, context).stream().map(AuditEventResponse::of).toList();
    }

    @GetMapping("/{auditEventId}")
    AuditEventResponse get(PrincipalContext principal, ExecutionContext context, @PathVariable String auditEventId) {
        return AuditEventResponse.of(audit.get(principal, Identifier.parse(auditEventId, "AuditEventId"), context));
    }

    @GetMapping("/trail")
    TrailResponse trail(PrincipalContext principal, ExecutionContext context, @RequestParam String transactionId) {
        AuditService.Trail trail = audit.trail(principal, Identifier.parse(transactionId, "TransactionId"), context);
        return new TrailResponse(trail.transactionId().toString(), trail.chain(),
                trail.events().stream().map(AuditEventResponse::of).toList());
    }

    private static UUID uuid(String value) {
        return value == null ? null : Identifier.parse(value, "Identifier");
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
