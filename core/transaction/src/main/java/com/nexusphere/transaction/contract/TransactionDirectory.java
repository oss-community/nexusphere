package com.nexusphere.transaction.contract;

import java.util.Optional;
import java.util.UUID;

public interface TransactionDirectory {

    Optional<TransactionSnapshot> find(UUID transactionId);
}
