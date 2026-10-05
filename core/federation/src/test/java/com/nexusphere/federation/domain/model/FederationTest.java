package com.nexusphere.federation.domain.model;

import com.nexusphere.federation.contract.FederationActivated;
import com.nexusphere.federation.contract.FederationProposed;
import com.nexusphere.federation.contract.FederationSubmitted;
import com.nexusphere.federation.contract.FederationTerminated;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.id.NetworkId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FederationTest {

    private static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

    private final NetworkId a = NetworkId.newId();
    private final NetworkId b = NetworkId.newId();

    private Federation proposed() {
        return Federation.propose(UUID.randomUUID(), a, b,
                List.of(FederationScope.CAPABILITY_DISCOVERY, FederationScope.AGREEMENT_CREATION), null, NOW);
    }

    @Test
    void federationBecomesActiveOnlyAfterThePartnerAccepts() {
        Federation federation = proposed();
        assertThat(federation.pullEvents()).singleElement().isInstanceOf(FederationProposed.class);

        federation.submit(a, NOW);
        assertThat(federation.status()).isEqualTo(FederationStatus.PENDING_ACCEPTANCE);
        assertThat(federation.isActive(NOW)).isFalse();
        assertThatThrownBy(() -> federation.accept(a, NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "FEDERATION_WRONG_PARTY");

        federation.accept(b, NOW);

        assertThat(federation.status()).isEqualTo(FederationStatus.ACTIVE);
        assertThat(federation.isActive(NOW)).isTrue();
        assertThat(federation.effectiveFrom()).contains(NOW);
        assertThat(federation.pullEvents()).hasSize(2).first().isInstanceOf(FederationSubmitted.class);
    }

    @Test
    void rejectionIsTerminal() {
        Federation federation = proposed();
        federation.submit(a, NOW);

        federation.reject(b, NOW);

        assertThat(federation.status()).isEqualTo(FederationStatus.REJECTED);
        assertThatThrownBy(() -> federation.accept(b, NOW))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "FEDERATION_INVALID_TRANSITION");
        assertThatThrownBy(() -> federation.terminate(a, NOW))
                .hasFieldOrPropertyWithValue("code", "FEDERATION_INVALID_TRANSITION");
    }

    @Test
    void onlyTheSuspendingNetworkResumesAndTerminationIsFinal() {
        Federation federation = proposed();
        federation.submit(a, NOW);
        federation.accept(b, NOW);

        federation.suspend(b, NOW);
        assertThat(federation.isActive(NOW)).isFalse();
        assertThat(federation.suspendedBy()).contains(b);
        assertThatThrownBy(() -> federation.resume(a, NOW))
                .hasFieldOrPropertyWithValue("code", "FEDERATION_WRONG_PARTY");
        federation.resume(b, NOW);
        assertThat(federation.isActive(NOW)).isTrue();
        federation.pullEvents();

        federation.terminate(a, NOW);

        assertThat(federation.status()).isEqualTo(FederationStatus.TERMINATED);
        assertThat(federation.pullEvents()).singleElement().isInstanceOf(FederationTerminated.class);
        assertThatThrownBy(() -> federation.resume(b, NOW))
                .hasFieldOrPropertyWithValue("code", "FEDERATION_INVALID_TRANSITION");
        assertThatThrownBy(() -> federation.suspend(NetworkId.newId(), NOW))
                .hasFieldOrPropertyWithValue("code", "FEDERATION_WRONG_PARTY");
    }

    @Test
    void federationExpires() {
        Federation federation = Federation.propose(UUID.randomUUID(), a, b,
                List.of(FederationScope.CAPABILITY_DISCOVERY), NOW.plus(Duration.ofDays(1)), NOW);
        federation.submit(a, NOW);
        federation.accept(b, NOW);

        assertThat(federation.isActive(NOW.plus(Duration.ofDays(2)))).isFalse();
        assertThat(federation.pullEvents()).last().isInstanceOf(FederationActivated.class);
    }

    @Test
    void invalidProposalsAreRejected() {
        assertThatThrownBy(() -> Federation.propose(UUID.randomUUID(), a, a,
                List.of(FederationScope.CAPABILITY_DISCOVERY), null, NOW))
                .hasFieldOrPropertyWithValue("code", "FEDERATION_WITH_ITSELF");
        assertThatThrownBy(() -> Federation.propose(UUID.randomUUID(), a, b, List.of(), null, NOW))
                .hasFieldOrPropertyWithValue("code", "FEDERATION_SCOPE_REQUIRED");
        assertThatThrownBy(() -> FederationScope.parse("payments"))
                .hasFieldOrPropertyWithValue("code", "INVALID_FEDERATION_SCOPE");
    }
}
