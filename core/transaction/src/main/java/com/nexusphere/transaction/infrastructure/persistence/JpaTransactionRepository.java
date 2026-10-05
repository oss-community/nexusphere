package com.nexusphere.transaction.infrastructure.persistence;

import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;
import com.nexusphere.transaction.domain.model.Authority;
import com.nexusphere.transaction.domain.model.Transaction;
import com.nexusphere.transaction.domain.model.TransactionParty;
import com.nexusphere.transaction.domain.repository.TransactionRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaTransactionRepository implements TransactionRepository {

    private final TransactionJpaRepository jpa;
    private final TransactionDocuments json;

    JpaTransactionRepository(TransactionJpaRepository jpa, TransactionDocuments json) {
        this.jpa = jpa;
        this.json = json;
    }

    @Override
    public Transaction save(Transaction transaction) {
        try {
            return toDomain(jpa.saveAndFlush(toEntity(transaction)));
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "Transaction " + transaction.id() + " was changed concurrently");
        }
    }

    @Override
    public Optional<Transaction> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public List<Transaction> findInvolving(NetworkId networkId) {
        return jpa.findInvolving(networkId.value()).stream().map(this::toDomain).toList();
    }

    private TransactionEntity toEntity(Transaction transaction) {
        Authority authority = transaction.authority();
        return new TransactionEntity(transaction.id(), transaction.type(), transaction.agreementId(),
                transaction.agreementVersion(), transaction.capabilityId().value(),
                transaction.capabilityNetworkId().value(), transaction.requester().organizationId().value(),
                transaction.requester().networkId().value(), transaction.provider().organizationId().value(),
                transaction.provider().networkId().value(), transaction.initiatingPrincipalId().value(),
                transaction.initiatingIdentityId().value(), authority.decisionId(), authority.delegationId(),
                authority.federationId(), authority.trustRelationshipId(), json.write(transaction.metadata()),
                transaction.status(), transaction.reason().orElse(null),
                json.write(transaction.result().orElse(null)),
                transaction.executorPrincipalId().map(PrincipalId::value).orElse(null),
                transaction.executorIdentityId().map(IdentityId::value).orElse(null),
                transaction.executionDecisionId().orElse(null), transaction.createdAt(),
                transaction.authorizedAt().orElse(null), transaction.startedAt().orElse(null),
                transaction.finishedAt().orElse(null), transaction.lockVersion());
    }

    private Transaction toDomain(TransactionEntity entity) {
        return Transaction.restore(entity.getId(), entity.getType(), entity.getAgreementId(),
                entity.getAgreementVersion(), new CapabilityId(entity.getCapabilityId()),
                new NetworkId(entity.getCapabilityNetworkId()),
                new TransactionParty(new OrganizationId(entity.getRequesterOrganizationId()),
                        new NetworkId(entity.getRequesterNetworkId())),
                new TransactionParty(new OrganizationId(entity.getProviderOrganizationId()),
                        new NetworkId(entity.getProviderNetworkId())),
                new PrincipalId(entity.getInitiatingPrincipalId()), new IdentityId(entity.getInitiatingIdentityId()),
                new Authority(entity.getDecisionId(), entity.getDelegationId(), entity.getFederationId(),
                        entity.getTrustRelationshipId()),
                json.read(entity.getMetadata()), entity.getStatus(), entity.getReason(), json.read(entity.getResult()),
                entity.getExecutorPrincipalId() == null ? null : new PrincipalId(entity.getExecutorPrincipalId()),
                entity.getExecutorIdentityId() == null ? null : new IdentityId(entity.getExecutorIdentityId()),
                entity.getExecutionDecisionId(), entity.getCreatedAt(), entity.getAuthorizedAt(),
                entity.getStartedAt(), entity.getFinishedAt(), entity.getVersion());
    }
}
