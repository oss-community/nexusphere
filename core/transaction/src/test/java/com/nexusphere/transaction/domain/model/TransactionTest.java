package com.nexusphere.transaction.domain.model;

import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.id.CapabilityId;
import com.nexusphere.shared.id.IdentityId;
import com.nexusphere.shared.id.NetworkId;
import com.nexusphere.shared.id.OrganizationId;
import com.nexusphere.shared.id.PrincipalId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionTest {

    private final TransactionParty requester = new TransactionParty(OrganizationId.newId(), NetworkId.newId());
    private final TransactionParty provider = new TransactionParty(OrganizationId.newId(), NetworkId.newId());
    private final CapabilityId capability = CapabilityId.newId();
    private final Participant agent = participant(requester);
    private final Participant machine = participant(provider);
    private final Instant now = Instant.parse("2026-10-05T10:00:00Z");

    private static Participant participant(TransactionParty party) {
        return new Participant(PrincipalId.newId(), IdentityId.newId(), party.networkId(), party.organizationId(),
                false);
    }

    private Transaction request(boolean active, CapabilityId requested) {
        return Transaction.request(UUID.randomUUID(), null, new Transaction.AgreementCoverage(UUID.randomUUID(), 1,
                        active, capability, provider.networkId(), requester, provider), requested, agent,
                new Authority(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null), Map.of(), now);
    }

    @Test
    void authorizedTransactionRunsToCompletion() {
        Transaction transaction = request(true, capability);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.AUTHORIZED);

        transaction.requireProvider(machine, null);
        transaction.execute(machine, UUID.randomUUID(), now);
        transaction.complete(Map.of("parts", 100), machine, now);

        assertThat(transaction.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transaction.executorIdentityId()).contains(machine.identityId());
        assertThat(transaction.pullEvents()).hasSize(4);
    }

    @Test
    void agreementValidationRejectsTheRequest() {
        assertThat(request(false, null).reason()).contains("AGREEMENT_NOT_ACTIVE");
        Transaction outside = request(true, CapabilityId.newId());
        assertThat(outside.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(outside.reason()).contains("CAPABILITY_NOT_COVERED");
        assertThatThrownBy(() -> outside.execute(machine, null, now)).isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyTheProviderExecutesAndOnlyBeforeExecutionCanTheRequesterCancel() {
        Transaction transaction = request(true, null);
        IdentityId owner = IdentityId.newId();

        assertThatThrownBy(() -> transaction.requireProvider(agent, owner)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "TRANSACTION_PROVIDER_REQUIRED");
        transaction.requireProvider(new Participant(PrincipalId.newId(), owner, provider.networkId(), null, false),
                owner);
        assertThatThrownBy(() -> transaction.requireRequester(machine)).isInstanceOf(DomainException.class);
        transaction.execute(machine, null, now);
        assertThatThrownBy(() -> transaction.cancel(agent, now)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "TRANSACTION_INVALID_TRANSITION");
        transaction.fail("spindle overheated", machine, now);
        assertThat(transaction.reason()).contains("spindle overheated");
    }
}
