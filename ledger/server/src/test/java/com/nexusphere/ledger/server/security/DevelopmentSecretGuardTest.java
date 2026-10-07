package com.nexusphere.ledger.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevelopmentSecretGuardTest {

    @Test
    void refusesDevelopmentSecretsOutsideTheDevProfile() {
        DevelopmentSecretGuard.DEVELOPMENT_SECRETS.forEach((property, secret) -> {
            MockEnvironment environment = new MockEnvironment().withProperty(property, secret);

            assertThatThrownBy(() -> new DevelopmentSecretGuard(environment).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(property);
        });
    }

    @Test
    void allowsDevelopmentSecretsInTheDevProfile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("ledger.security.api-key", "nexusphere-ledger-development-key-change-me");
        environment.setActiveProfiles(DevelopmentSecretGuard.DEVELOPMENT_PROFILE);

        assertThatCode(() -> new DevelopmentSecretGuard(environment).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
