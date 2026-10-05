package com.nexusphere.transaction.application;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.transaction.contract.TransactionCommands;
import com.nexusphere.transaction.contract.TransactionRequest;
import com.nexusphere.transaction.contract.TransactionSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
class TransactionCommandService implements TransactionCommands {

    private final TransactionService transactions;

    TransactionCommandService(TransactionService transactions) {
        this.transactions = transactions;
    }

    @Override
    public TransactionSnapshot request(PrincipalContext principal, TransactionRequest request,
                                       ExecutionContext context) {
        return TransactionService.snapshot(transactions.request(principal, new TransactionService.Request(
                request.agreementId(), request.capabilityId() == null ? null : request.capabilityId().toString(),
                request.type(), request.metadata(), request.delegationId()), context));
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionSnapshot read(PrincipalContext principal, UUID transactionId) {
        return TransactionService.snapshot(transactions.get(principal, transactionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionSnapshot> assignedTo(PrincipalContext principal) {
        return transactions.assignedTo(principal).stream().map(TransactionService::snapshot).toList();
    }

    @Override
    public TransactionSnapshot execute(PrincipalContext principal, UUID transactionId, ExecutionContext context) {
        return TransactionService.snapshot(transactions.execute(principal, transactionId, context));
    }

    @Override
    public TransactionSnapshot complete(PrincipalContext principal, UUID transactionId, Map<String, Object> result,
                                        ExecutionContext context) {
        return TransactionService.snapshot(transactions.complete(principal, transactionId, result, context));
    }

    @Override
    public TransactionSnapshot fail(PrincipalContext principal, UUID transactionId, String reason,
                                    ExecutionContext context) {
        return TransactionService.snapshot(transactions.fail(principal, transactionId, reason, context));
    }
}
