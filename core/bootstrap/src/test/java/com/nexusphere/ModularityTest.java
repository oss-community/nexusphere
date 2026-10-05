package com.nexusphere;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring Modulith view of the module boundaries: no cycles, no access to another module's internals. */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(NexusphereApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        modules.verify();
    }

    @Test
    void everyCoreBoundedContextIsAModule() {
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
                .contains("network", "organization", "identity", "membership", "capability", "discovery",
                        "trust", "federation", "authorization", "delegation", "agreement", "transaction",
                        "audit", "integration", "bootstrap");
    }
}
