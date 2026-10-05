package com.nexusphere.transaction.contract;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TransactionCommands {

    TransactionSnapshot request(PrincipalContext principal, TransactionRequest request, ExecutionContext context);

    TransactionSnapshot read(PrincipalContext principal, UUID transactionId);

    List<TransactionSnapshot> assignedTo(PrincipalContext principal);

    TransactionSnapshot execute(PrincipalContext principal, UUID transactionId, ExecutionContext context);

    TransactionSnapshot complete(PrincipalContext principal, UUID transactionId, Map<String, Object> result,
                                 ExecutionContext context);

    TransactionSnapshot fail(PrincipalContext principal, UUID transactionId, String reason, ExecutionContext context);
}
