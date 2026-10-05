package com.nexusphere.transaction.api.rest;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.transaction.application.TransactionService;
import com.nexusphere.transaction.domain.model.Authority;
import com.nexusphere.transaction.domain.model.Transaction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/transactions")
class TransactionController {

    record RequestTransactionRequest(String agreementId, String capabilityId, String type,
                                     Map<String, Object> metadata, String delegationId) {
    }

    record CompleteRequest(Map<String, Object> result) {
    }

    record FailRequest(String reason) {
    }

    record TransactionResponse(String id, String type, String status, String reason, String agreementId,
                               int agreementVersion, String capabilityId, String capabilityNetworkId,
                               String requesterOrganizationId, String requesterNetworkId,
                               String providerOrganizationId, String providerNetworkId,
                               String initiatingPrincipalId, String initiatingIdentityId, String decisionId,
                               String delegationId, String federationId, String trustRelationshipId,
                               Map<String, Object> metadata, Map<String, Object> result, String executorPrincipalId,
                               String executorIdentityId, String executionDecisionId, Instant createdAt,
                               Instant authorizedAt, Instant startedAt, Instant finishedAt) {

        static TransactionResponse of(Transaction transaction) {
            Authority authority = transaction.authority();
            return new TransactionResponse(transaction.id().toString(), transaction.type(),
                    transaction.status().name(), transaction.reason().orElse(null),
                    transaction.agreementId().toString(), transaction.agreementVersion(),
                    transaction.capabilityId().toString(), transaction.capabilityNetworkId().toString(),
                    transaction.requester().organizationId().toString(),
                    transaction.requester().networkId().toString(),
                    transaction.provider().organizationId().toString(), transaction.provider().networkId().toString(),
                    transaction.initiatingPrincipalId().toString(), transaction.initiatingIdentityId().toString(),
                    text(authority.decisionId()), text(authority.delegationId()), text(authority.federationId()),
                    text(authority.trustRelationshipId()), transaction.metadata(), transaction.result().orElse(null),
                    text(transaction.executorPrincipalId().orElse(null)),
                    text(transaction.executorIdentityId().orElse(null)),
                    text(transaction.executionDecisionId().orElse(null)), transaction.createdAt(),
                    transaction.authorizedAt().orElse(null), transaction.startedAt().orElse(null),
                    transaction.finishedAt().orElse(null));
        }
    }

    private final TransactionService transactions;

    TransactionController(TransactionService transactions) {
        this.transactions = transactions;
    }

    @PostMapping
    ResponseEntity<TransactionResponse> request(@PathVariable String networkId,
                                                @RequestBody RequestTransactionRequest request,
                                                PrincipalContext principal, ExecutionContext context) {
        Transaction requested = transactions.request(principal, new TransactionService.Request(
                request.agreementId() == null ? null : id(request.agreementId(), "AgreementId"),
                request.capabilityId(), request.type(), request.metadata(),
                request.delegationId() == null ? null : id(request.delegationId(), "DelegationId")), context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + networkId + "/transactions/"
                + requested.id())).body(TransactionResponse.of(requested));
    }

    @GetMapping
    List<TransactionResponse> list(PrincipalContext principal, @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String agreementId,
                                   @RequestParam(required = false) String capabilityId) {
        return transactions.list(principal, new TransactionService.Filter(status,
                        agreementId == null ? null : id(agreementId, "AgreementId"), capabilityId)).stream()
                .map(TransactionResponse::of).toList();
    }

    @GetMapping("/{transactionId}")
    TransactionResponse get(PrincipalContext principal, @PathVariable String transactionId) {
        return TransactionResponse.of(transactions.get(principal, id(transactionId, "TransactionId")));
    }

    @PostMapping("/{transactionId}/execute")
    TransactionResponse execute(PrincipalContext principal, @PathVariable String transactionId,
                                ExecutionContext context) {
        return TransactionResponse.of(transactions.execute(principal, id(transactionId, "TransactionId"), context));
    }

    @PostMapping("/{transactionId}/complete")
    TransactionResponse complete(PrincipalContext principal, @PathVariable String transactionId,
                                 @RequestBody(required = false) CompleteRequest request, ExecutionContext context) {
        return TransactionResponse.of(transactions.complete(principal, id(transactionId, "TransactionId"),
                request == null ? null : request.result(), context));
    }

    @PostMapping("/{transactionId}/fail")
    TransactionResponse fail(PrincipalContext principal, @PathVariable String transactionId,
                             @RequestBody(required = false) FailRequest request, ExecutionContext context) {
        return TransactionResponse.of(transactions.fail(principal, id(transactionId, "TransactionId"),
                request == null ? null : request.reason(), context));
    }

    @PostMapping("/{transactionId}/cancel")
    TransactionResponse cancel(PrincipalContext principal, @PathVariable String transactionId,
                               ExecutionContext context) {
        return TransactionResponse.of(transactions.cancel(principal, id(transactionId, "TransactionId"), context));
    }

    private static UUID id(String value, String field) {
        return Identifier.parse(value, field);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
