package com.nexusphere.transaction.application;

import com.nexusphere.agreement.contract.AgreementDirectory;
import com.nexusphere.agreement.contract.AgreementSnapshot;
import com.nexusphere.authorization.contract.Actions;
import com.nexusphere.authorization.contract.AuthorizationDecision;
import com.nexusphere.authorization.contract.AuthorizationRequest;
import com.nexusphere.authorization.contract.Authorizer;
import com.nexusphere.authorization.contract.ResourceOwner;
import com.nexusphere.capability.contract.CapabilityDirectory;
import com.nexusphere.membership.contract.PrincipalContext;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ErrorCategory;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.event.DomainEventPublisher;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.reference.ResourceReference;
import com.nexusphere.shared.time.TimeProvider;
import com.nexusphere.transaction.contract.TransactionDirectory;
import com.nexusphere.transaction.contract.TransactionSnapshot;
import com.nexusphere.transaction.domain.model.Authority;
import com.nexusphere.transaction.domain.model.Participant;
import com.nexusphere.transaction.domain.model.Transaction;
import com.nexusphere.transaction.domain.model.TransactionParty;
import com.nexusphere.transaction.domain.model.TransactionStatus;
import com.nexusphere.transaction.domain.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class TransactionService implements TransactionDirectory {

    public record Request(UUID agreementId, String capabilityId, String type, Map<String, Object> metadata,
                          UUID delegationId) {
    }

    public record Filter(String status, UUID agreementId, String capabilityId) {
    }

    private final TransactionRepository transactions;
    private final AgreementDirectory agreements;
    private final CapabilityDirectory capabilities;
    private final Authorizer authorizer;
    private final DomainEventPublisher events;
    private final TimeProvider time;

    TransactionService(TransactionRepository transactions, AgreementDirectory agreements,
                       CapabilityDirectory capabilities, Authorizer authorizer, DomainEventPublisher events,
                       TimeProvider time) {
        this.transactions = transactions;
        this.agreements = agreements;
        this.capabilities = capabilities;
        this.authorizer = authorizer;
        this.events = events;
        this.time = time;
    }

    public Transaction request(PrincipalContext principal, Request request, ExecutionContext context) {
        if (request.agreementId() == null) {
            throw new ValidationException("AGREEMENT_REQUIRED", "A transaction must name its agreement");
        }
        AgreementSnapshot agreement = agreements.find(request.agreementId())
                .filter(found -> found.involves(principal.networkId()))
                .orElseThrow(() -> new NotFoundException("Agreement", request.agreementId()));
        Participant participant = participant(principal);
        TransactionParty requester = new TransactionParty(agreement.proposerOrganizationId(),
                agreement.proposerNetworkId());
        TransactionParty provider = new TransactionParty(agreement.counterpartyOrganizationId(),
                agreement.counterpartyNetworkId());
        if (!requester.represents(participant)) {
            String code = provider.represents(participant) ? "TRANSACTION_REQUESTER_REQUIRED"
                    : "TRANSACTION_PARTY_REQUIRED";
            throw new DomainException(ErrorCategory.AUTHORIZATION_ERROR, code,
                    "Only the consuming party of agreement " + agreement.id() + " can request transactions");
        }
        UUID id = UUID.randomUUID();
        AuthorizationDecision decision = authorizer.require(AuthorizationRequest.of(principal,
                        Actions.TRANSACTION_INITIATE, new ResourceReference("transaction", id.toString(),
                                provider.networkId()))
                .withCapabilityType(agreement.capabilityTypeCode()).withDelegation(request.delegationId()), context);
        Transaction transaction = Transaction.request(id, request.type(), new Transaction.AgreementCoverage(
                        agreement.id(), agreement.currentVersion(), agreement.active(), agreement.capabilityId(),
                        agreement.capabilityNetworkId(), requester, provider),
                request.capabilityId() == null ? null : CapabilityId.of(request.capabilityId()), participant,
                new Authority(decision.id(), decision.delegationId(), decision.federationId(),
                        decision.trustRelationshipId()),
                request.metadata(), time.now());
        return persist(transaction, context);
    }

    public Transaction execute(PrincipalContext principal, UUID id, ExecutionContext context) {
        Transaction transaction = load(principal, id);
        Participant participant = participant(principal);
        AuthorizationDecision decision = requireProvider(principal, transaction, participant, context);
        transaction.execute(participant, decision.id(), time.now());
        return persist(transaction, context);
    }

    public Transaction complete(PrincipalContext principal, UUID id, Map<String, Object> result,
                                ExecutionContext context) {
        Transaction transaction = load(principal, id);
        Participant participant = participant(principal);
        requireProvider(principal, transaction, participant, context);
        transaction.complete(result, participant, time.now());
        return persist(transaction, context);
    }

    public Transaction fail(PrincipalContext principal, UUID id, String reason, ExecutionContext context) {
        Transaction transaction = load(principal, id);
        Participant participant = participant(principal);
        requireProvider(principal, transaction, participant, context);
        transaction.fail(reason, participant, time.now());
        return persist(transaction, context);
    }

    public Transaction cancel(PrincipalContext principal, UUID id, ExecutionContext context) {
        Transaction transaction = load(principal, id);
        Participant participant = participant(principal);
        transaction.requireRequester(participant);
        authorizer.require(AuthorizationRequest.of(principal, Actions.TRANSACTION_INITIATE,
                new ResourceReference("transaction", id.toString(), transaction.provider().networkId())), context);
        transaction.cancel(participant, time.now());
        return persist(transaction, context);
    }

    @Transactional(readOnly = true)
    public Transaction get(PrincipalContext principal, UUID id) {
        return transactions.findById(id).filter(transaction -> readable(principal, transaction))
                .orElseThrow(() -> new NotFoundException("Transaction", id));
    }

    @Transactional(readOnly = true)
    public List<Transaction> list(PrincipalContext principal, Filter filter) {
        TransactionStatus status = filter.status() == null ? null : status(filter.status());
        CapabilityId capability = filter.capabilityId() == null ? null : CapabilityId.of(filter.capabilityId());
        return transactions.findInvolving(principal.networkId()).stream()
                .filter(transaction -> readable(principal, transaction))
                .filter(transaction -> status == null || transaction.status() == status)
                .filter(transaction -> filter.agreementId() == null
                        || transaction.agreementId().equals(filter.agreementId()))
                .filter(transaction -> capability == null || transaction.capabilityId().equals(capability))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TransactionSnapshot> find(UUID transactionId) {
        return transactions.findById(transactionId).map(TransactionService::snapshot);
    }

    private AuthorizationDecision requireProvider(PrincipalContext principal, Transaction transaction,
                                                  Participant participant, ExecutionContext context) {
        IdentityId owner = capabilityOwner(transaction);
        transaction.requireProvider(participant, owner);
        return authorizer.require(AuthorizationRequest.of(principal, Actions.TRANSACTION_EXECUTE,
                        new ResourceReference("transaction", transaction.id().toString(), principal.networkId()))
                .withOwner(new ResourceOwner(transaction.provider().organizationId(), owner)), context);
    }

    private IdentityId capabilityOwner(Transaction transaction) {
        return capabilities.find(transaction.capabilityNetworkId(), transaction.capabilityId())
                .filter(capability -> !"ORGANIZATION".equals(capability.ownerType()))
                .map(capability -> new IdentityId(capability.ownerId())).orElse(null);
    }

    private boolean readable(PrincipalContext principal, Transaction transaction) {
        if (!transaction.involves(principal.networkId())) {
            return false;
        }
        return transaction.isParty(participant(principal))
                || principal.identityId().equals(capabilityOwner(transaction))
                || authorizer.actionsHeldBy(principal).contains(Actions.AUDIT_READ);
    }

    private Transaction load(PrincipalContext principal, UUID id) {
        return transactions.findById(id).filter(transaction -> transaction.involves(principal.networkId()))
                .orElseThrow(() -> new NotFoundException("Transaction", id));
    }

    private static Participant participant(PrincipalContext principal) {
        return new Participant(principal.principalId(), principal.identityId(), principal.networkId(),
                principal.organizationId(), principal.networkAdministrator());
    }

    private static TransactionStatus status(String value) {
        return Arrays.stream(TransactionStatus.values()).filter(status -> status.name().equalsIgnoreCase(value.trim()))
                .findFirst().orElseThrow(() -> new ValidationException("INVALID_TRANSACTION_STATUS",
                        "The status must be one of " + Arrays.toString(TransactionStatus.values())));
    }

    private Transaction persist(Transaction transaction, ExecutionContext context) {
        Transaction saved = transactions.save(transaction);
        events.publishAll(transaction.pullEvents(), context.withNetwork(transaction.networkId()));
        return saved;
    }

    static TransactionSnapshot snapshot(Transaction transaction) {
        Authority authority = transaction.authority();
        return new TransactionSnapshot(transaction.id(), transaction.type(), transaction.status().name(),
                transaction.reason().orElse(null), transaction.agreementId(), transaction.agreementVersion(),
                transaction.capabilityId(), transaction.capabilityNetworkId(),
                transaction.requester().organizationId(), transaction.requester().networkId(),
                transaction.provider().organizationId(), transaction.provider().networkId(),
                transaction.initiatingPrincipalId(), transaction.initiatingIdentityId(), authority.decisionId(),
                authority.delegationId(), authority.federationId(), transaction.executorPrincipalId().orElse(null),
                transaction.executorIdentityId().orElse(null));
    }
}
