package com.nexusphere.agreement.api.rest;

import com.nexusphere.agreement.application.AgreementService;
import com.nexusphere.agreement.domain.model.Agreement;
import com.nexusphere.agreement.domain.model.AgreementParty;
import com.nexusphere.agreement.domain.model.AgreementVersion;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.Identifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/agreements")
class AgreementController {

    record CreateAgreementRequest(String capabilityId, String type, String title, Map<String, Object> terms,
                                  String delegationId) {
    }

    record ProposeRequest(Integer expectedVersion, String delegationId) {
    }

    record ReviseRequest(Map<String, Object> terms, Integer expectedVersion, String delegationId) {
    }

    record DecideRequest(Integer version, String reason, String delegationId) {
    }

    record CloseRequest(String reason) {
    }

    record PartyBody(String organizationId, String networkId) {

        static PartyBody of(AgreementParty party) {
            return new PartyBody(party.organizationId().toString(), party.networkId().toString());
        }
    }

    record VersionResponse(int number, boolean superseded, Map<String, Object> terms, List<String> changes,
                           PartyBody onBehalfOf, String proposedBy, String proposedByIdentity, String delegationId,
                           String decisionId, String federationId, Instant proposedAt, String acceptedBy,
                           Instant acceptedAt, String acceptanceDecisionId) {

        static VersionResponse of(AgreementVersion version) {
            return new VersionResponse(version.number(), version.superseded(), version.terms(), version.changes(),
                    PartyBody.of(version.onBehalfOf()), version.proposedBy().toString(),
                    version.proposedByIdentity().toString(), text(version.delegationId().orElse(null)),
                    text(version.decisionId().orElse(null)), text(version.federationId().orElse(null)),
                    version.proposedAt(), text(version.acceptedBy().orElse(null)), version.acceptedAt().orElse(null),
                    text(version.acceptanceDecisionId().orElse(null)));
        }
    }

    record AgreementResponse(String id, String networkId, String type, String title, String status,
                             String capabilityId, String capabilityNetworkId, String capabilityTypeCode,
                             PartyBody proposer, PartyBody counterparty, int currentVersion,
                             VersionResponse version, Instant createdAt, Instant activatedAt, Instant closedAt,
                             String closingReason) {

        static AgreementResponse of(Agreement agreement) {
            return new AgreementResponse(agreement.id().toString(), agreement.networkId().toString(),
                    agreement.type(), agreement.title(), agreement.status().name(),
                    agreement.capabilityId().toString(), agreement.capabilityNetworkId().toString(),
                    agreement.capabilityTypeCode(), PartyBody.of(agreement.proposer()),
                    PartyBody.of(agreement.counterparty()), agreement.current().number(),
                    VersionResponse.of(agreement.current()), agreement.createdAt(),
                    agreement.activatedAt().orElse(null), agreement.closedAt().orElse(null),
                    agreement.closingReason().orElse(null));
        }
    }

    private final AgreementService agreements;

    AgreementController(AgreementService agreements) {
        this.agreements = agreements;
    }

    @PostMapping
    ResponseEntity<AgreementResponse> create(@PathVariable String networkId,
                                             @RequestBody CreateAgreementRequest request,
                                             PrincipalContext principal, ExecutionContext context) {
        Agreement created = agreements.create(principal, new AgreementService.Draft(request.capabilityId(),
                request.type(), request.title(), request.terms(), delegation(request.delegationId())), context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + networkId + "/agreements/" + created.id()))
                .body(AgreementResponse.of(created));
    }

    @GetMapping
    List<AgreementResponse> list(PrincipalContext principal) {
        return agreements.list(principal).stream().map(AgreementResponse::of).toList();
    }

    @GetMapping("/{agreementId}")
    AgreementResponse get(PrincipalContext principal, @PathVariable String agreementId) {
        return AgreementResponse.of(agreements.get(principal, id(agreementId)));
    }

    @GetMapping("/{agreementId}/versions")
    List<VersionResponse> versions(PrincipalContext principal, @PathVariable String agreementId) {
        return agreements.get(principal, id(agreementId)).versions().stream().map(VersionResponse::of).toList();
    }

    @GetMapping("/{agreementId}/versions/{number}")
    VersionResponse version(PrincipalContext principal, @PathVariable String agreementId, @PathVariable int number) {
        return agreements.get(principal, id(agreementId)).version(number).map(VersionResponse::of)
                .orElseThrow(() -> new NotFoundException("AgreementVersion", number));
    }

    @PostMapping("/{agreementId}/propose")
    AgreementResponse propose(PrincipalContext principal, @PathVariable String agreementId,
                              @RequestBody(required = false) ProposeRequest request, ExecutionContext context) {
        ProposeRequest body = request == null ? new ProposeRequest(null, null) : request;
        return AgreementResponse.of(agreements.propose(principal, id(agreementId), body.expectedVersion(),
                delegation(body.delegationId()), context));
    }

    @PostMapping("/{agreementId}/revisions")
    AgreementResponse revise(PrincipalContext principal, @PathVariable String agreementId,
                             @RequestBody ReviseRequest request, ExecutionContext context) {
        return AgreementResponse.of(agreements.revise(principal, id(agreementId), request.terms(),
                request.expectedVersion(), delegation(request.delegationId()), context));
    }

    @PostMapping("/{agreementId}/accept")
    AgreementResponse accept(PrincipalContext principal, @PathVariable String agreementId,
                             @RequestBody DecideRequest request, ExecutionContext context) {
        return AgreementResponse.of(agreements.accept(principal, id(agreementId), version(request),
                delegation(request.delegationId()), context));
    }

    @PostMapping("/{agreementId}/reject")
    AgreementResponse reject(PrincipalContext principal, @PathVariable String agreementId,
                             @RequestBody DecideRequest request, ExecutionContext context) {
        return AgreementResponse.of(agreements.reject(principal, id(agreementId), version(request), request.reason(),
                delegation(request.delegationId()), context));
    }

    @PostMapping("/{agreementId}/activate")
    AgreementResponse activate(PrincipalContext principal, @PathVariable String agreementId,
                               ExecutionContext context) {
        return AgreementResponse.of(agreements.activate(principal, id(agreementId), context));
    }

    @PostMapping("/{agreementId}/complete")
    AgreementResponse complete(PrincipalContext principal, @PathVariable String agreementId,
                               ExecutionContext context) {
        return AgreementResponse.of(agreements.complete(principal, id(agreementId), context));
    }

    @PostMapping("/{agreementId}/terminate")
    AgreementResponse terminate(PrincipalContext principal, @PathVariable String agreementId,
                                @RequestBody(required = false) CloseRequest request, ExecutionContext context) {
        return AgreementResponse.of(agreements.terminate(principal, id(agreementId),
                request == null ? null : request.reason(), context));
    }

    private static UUID id(String value) {
        return Identifier.parse(value, "AgreementId");
    }

    private static UUID delegation(String value) {
        return value == null ? null : Identifier.parse(value, "DelegationId");
    }

    private static int version(DecideRequest request) {
        if (request.version() == null) {
            throw new ValidationException("AGREEMENT_VERSION_REQUIRED", "The agreement version must be specified");
        }
        return request.version();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
