package com.nexusphere.transaction.application;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.transaction.contract.TransactionCommands;
import com.nexusphere.transaction.contract.TransactionRequest;
import com.nexusphere.transaction.contract.TransactionSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
