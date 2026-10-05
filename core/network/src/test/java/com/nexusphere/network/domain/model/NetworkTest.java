package com.nexusphere.network.domain.model;

import com.nexusphere.network.contract.NetworkActivated;
import com.nexusphere.network.contract.NetworkCreated;
import com.nexusphere.network.contract.NetworkSuspended;
import com.nexusphere.shared.error.ConflictException;
import com.nexusphere.shared.error.ValidationException;
import com.nexusphere.shared.id.NetworkId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkTest {

    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    @Test
    void createdNetworkIsPendingWithTrimmedNameAndEvent() {
        Network network = Network.create(NetworkId.newId(), "  Network A  ", " ", NOW);

        assertThat(network.status()).isEqualTo(NetworkStatus.PENDING);
        assertThat(network.name()).isEqualTo("Network A");
        assertThat(network.description()).isNull();
        assertThat(network.pullEvents()).singleElement().isInstanceOf(NetworkCreated.class);
    }

    @Test
    void blankNameIsRejected() {
        assertThatThrownBy(() -> Network.create(NetworkId.newId(), "  ", null, NOW))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_NETWORK_NAME");
    }

    @Test
    void lifecycleMovesThroughActiveSuspendedAndBack() {
        Network network = Network.create(NetworkId.newId(), "A", null, NOW);
        network.pullEvents();

        network.activate(NOW);
        network.suspend(NOW);
        network.activate(NOW);

        assertThat(network.isActive()).isTrue();
        assertThat(network.pullEvents()).hasExactlyElementsOfTypes(
                NetworkActivated.class, NetworkSuspended.class, NetworkActivated.class);
    }

    @Test
    void activatingAnActiveNetworkIsAConflict() {
        Network network = Network.create(NetworkId.newId(), "A", null, NOW);
        network.activate(NOW);

        assertThatThrownBy(() -> network.activate(NOW))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "NETWORK_ALREADY_ACTIVE");
    }

    @Test
    void archivedNetworkCannotChangeAnymore() {
        Network network = Network.create(NetworkId.newId(), "A", null, NOW);
        network.archive(NOW);

        assertThatThrownBy(() -> network.activate(NOW))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_NETWORK_TRANSITION");
        assertThatThrownBy(() -> network.suspend(NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_NETWORK_TRANSITION");
    }

    @Test
    void onlyAnActiveNetworkCanBeSuspended() {
        Network network = Network.create(NetworkId.newId(), "A", null, NOW);

        assertThatThrownBy(() -> network.suspend(NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_NETWORK_TRANSITION");
    }
}
