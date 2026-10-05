package com.nexusphere.agreement.domain.model;

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

class AgreementTest {

    private final NetworkId networkA = NetworkId.newId();
    private final NetworkId networkB = NetworkId.newId();
    private final AgreementParty organizationA = new AgreementParty(OrganizationId.newId(), networkA);
    private final AgreementParty organizationB = new AgreementParty(OrganizationId.newId(), networkB);
    private final Actor agent = actor(organizationA);
    private final Actor human = actor(organizationB);
    private final Instant now = Instant.parse("2026-10-05T10:00:00Z");

    private static Actor actor(AgreementParty party) {
        return new Actor(PrincipalId.newId(), IdentityId.newId(), party.networkId(), party.organizationId(), false,
                UUID.randomUUID(), UUID.randomUUID(), null);
    }

    private Agreement draft() {
        return Agreement.draft(UUID.randomUUID(), null, "CNC parts", CapabilityId.newId(), networkB,
                "manufacturing.cnc", organizationA, organizationB, Map.of("quantity", 100), agent, now);
    }

    @Test
    void lifecycleRunsFromDraftToCompleted() {
        Agreement agreement = draft();

        agreement.propose(1, agent, now);
        agreement.accept(1, human, now);
        agreement.activate(human, now);
        agreement.complete(agent, now);

        assertThat(agreement.status()).isEqualTo(AgreementStatus.COMPLETED);
        assertThat(agreement.current().acceptedBy()).contains(human.principalId());
        assertThat(agreement.current().changes()).containsExactly("quantity");
        assertThat(agreement.pullEvents()).hasSize(5);
    }

    @Test
    void revisionSupersedesThePreviousVersion() {
        Agreement agreement = draft();
        agreement.propose(null, agent, now);

        agreement.revise(Map.of("quantity", 80, "price", 10), 1, human, now);

        assertThat(agreement.current().number()).isEqualTo(2);
        assertThat(agreement.current().changes()).containsExactly("price", "quantity");
        assertThat(agreement.current().onBehalfOf()).isEqualTo(organizationB);
        assertThat(agreement.version(1).orElseThrow().superseded()).isTrue();
        assertThatThrownBy(() -> agreement.accept(1, agent, now)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "AGREEMENT_VERSION_SUPERSEDED");
        assertThatThrownBy(() -> agreement.accept(2, human, now)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "AGREEMENT_SELF_ACCEPTANCE");
        assertThatThrownBy(() -> agreement.revise(Map.of(), 1, agent, now)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "AGREEMENT_VERSION_CONFLICT");
        agreement.accept(2, agent, now);
        assertThat(agreement.status()).isEqualTo(AgreementStatus.ACCEPTED);
    }

    @Test
    void invalidTransitionsAndOutsidersAreRejected() {
        Agreement agreement = draft();
        Actor outsider = actor(new AgreementParty(OrganizationId.newId(), networkA));

        assertThatThrownBy(() -> agreement.activate(agent, now)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "AGREEMENT_INVALID_TRANSITION");
        assertThatThrownBy(() -> agreement.terminate(null, outsider, now)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "AGREEMENT_PARTY_REQUIRED");
        assertThatThrownBy(() -> agreement.propose(null, human, now)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "AGREEMENT_PARTY_REQUIRED");
        assertThatThrownBy(() -> Agreement.draft(UUID.randomUUID(), null, "Self", CapabilityId.newId(), networkA,
                "manufacturing.cnc", organizationA, organizationA, Map.of(), agent, now))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "AGREEMENT_PARTIES_IDENTICAL");
        agreement.terminate("no longer needed", agent, now);
        assertThat(agreement.closingReason()).contains("no longer needed");
    }
}
