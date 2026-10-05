package com.nexusphere.transaction.domain.repository;

import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.transaction.domain.model.Transaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository {

    Transaction save(Transaction transaction);

    Optional<Transaction> findById(UUID id);

    List<Transaction> findInvolving(NetworkId networkId);
}
