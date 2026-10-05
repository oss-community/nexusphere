package com.nexusphere.transaction.contract;

import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;

import java.util.UUID;

public interface TransactionCommands {

    TransactionSnapshot request(PrincipalContext principal, TransactionRequest request, ExecutionContext context);

    TransactionSnapshot read(PrincipalContext principal, UUID transactionId);
}
