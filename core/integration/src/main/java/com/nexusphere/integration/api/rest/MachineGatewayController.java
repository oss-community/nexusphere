package com.nexusphere.integration.api.rest;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.id.Identifier;
import com.nexusphere.transaction.contract.TransactionCommands;
import com.nexusphere.transaction.contract.TransactionSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/networks/{networkId}/machine/tasks")
class MachineGatewayController {

    record CompleteRequest(Map<String, Object> result) {
    }

    record FailRequest(String reason) {
    }

    record TaskResponse(String id, String type, String status, String reason, String agreementId,
                        int agreementVersion, String capabilityId, String requesterOrganizationId,
                        String requesterNetworkId, String providerOrganizationId, String executorPrincipalId,
                        String executorIdentityId, Map<String, Object> metadata, Map<String, Object> result) {

        static TaskResponse of(TransactionSnapshot transaction) {
            return new TaskResponse(text(transaction.id()), transaction.type(), transaction.status(),
                    transaction.reason(), text(transaction.agreementId()), transaction.agreementVersion(),
                    text(transaction.capabilityId()), text(transaction.requesterOrganizationId()),
                    text(transaction.requesterNetworkId()), text(transaction.providerOrganizationId()),
                    text(transaction.executorPrincipalId()), text(transaction.executorIdentityId()),
                    transaction.metadata(), transaction.result());
        }
    }

    private final TransactionCommands transactions;

    MachineGatewayController(TransactionCommands transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    List<TaskResponse> tasks(@PathVariable String networkId, PrincipalContext principal) {
        return transactions.assignedTo(machine(principal)).stream().map(TaskResponse::of).toList();
    }

    @PostMapping("/{taskId}/start")
    TaskResponse start(@PathVariable String networkId, @PathVariable String taskId, PrincipalContext principal,
                       ExecutionContext context) {
        return TaskResponse.of(transactions.execute(principal, assigned(principal, taskId), context));
    }

    @PostMapping("/{taskId}/complete")
    TaskResponse complete(@PathVariable String networkId, @PathVariable String taskId,
                          @RequestBody(required = false) CompleteRequest request, PrincipalContext principal,
                          ExecutionContext context) {
        return TaskResponse.of(transactions.complete(principal, assigned(principal, taskId),
                request == null ? null : request.result(), context));
    }

    @PostMapping("/{taskId}/fail")
    TaskResponse fail(@PathVariable String networkId, @PathVariable String taskId,
                      @RequestBody(required = false) FailRequest request, PrincipalContext principal,
                      ExecutionContext context) {
        return TaskResponse.of(transactions.fail(principal, assigned(principal, taskId),
                request == null ? null : request.reason(), context));
    }

    private static PrincipalContext machine(PrincipalContext principal) {
        if (!"MACHINE".equals(principal.identityType())) {
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, "MACHINE_IDENTITY_REQUIRED",
                    "Only a machine identity can work on machine tasks");
        }
        return principal;
    }

    private UUID assigned(PrincipalContext principal, String taskId) {
        UUID id = Identifier.parse(taskId, "TransactionId");
        return transactions.assignedTo(machine(principal)).stream().map(TransactionSnapshot::id)
                .filter(id::equals).findFirst().orElseThrow(() -> new NotFoundException("MachineTask", id));
    }

    private static String text(Object value) {
        return Objects.toString(value, null);
    }
}
