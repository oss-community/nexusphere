package com.nexusphere.bootstrap.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevelopmentSecretGuardTest {

    private static final String PUBLISHED = "nexusphere-development-operator-secret-change-me";

    @Test
    void thePublishedDevelopmentSecretsAreRejectedOutsideTheDevelopmentProfile() {
        MockEnvironment production = new MockEnvironment()
                .withProperty("nexusphere.security.operator.secret", PUBLISHED);
        MockEnvironment development = new MockEnvironment()
                .withProperty("nexusphere.security.operator.secret", PUBLISHED);
        development.setActiveProfiles("postgresql", "dev");
        MockEnvironment configured = new MockEnvironment()
                .withProperty("nexusphere.security.operator.secret", "a-real-secret-of-at-least-thirty-two-bytes");

        assertThatThrownBy(() -> new DevelopmentSecretGuard(production).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nexusphere.security.operator.secret");
        assertThatCode(() -> new DevelopmentSecretGuard(development).afterPropertiesSet()).doesNotThrowAnyException();
        assertThatCode(() -> new DevelopmentSecretGuard(configured).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
