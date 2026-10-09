package com.nexusphere.e2e.security;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitE2ETest extends E2ETestBase {

    @DynamicPropertySource
    static void limit(DynamicPropertyRegistry registry) {
        registry.add("nexusphere.rate-limit.per-minute", () -> "6");
        registry.add("nexusphere.rate-limit.burst", () -> "3");
    }

    @Test
    @DisplayName("E2E-SEC-RL-01 a caller over its limit gets 429 with Retry-After, other callers and probes do not")
    void callersAreLimitedOneByOne() {
        ApiClient first = api().asOperator();
        ApiClient anonymous = api();

        for (int i = 0; i < 3; i++) {
            assertThat(first.get("/api/v1/platform").status()).isEqualTo(200);
        }
        ApiClient.Response limited = first.get("/api/v1/platform");

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.json().path("code").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(limited.json().path("category").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(limited.json().path("correlationId").asString()).isNotBlank();
        assertThat(limited.header("Retry-After")).hasValueSatisfying(v -> assertThat(Long.parseLong(v)).isPositive());
        assertThat(anonymous.get("/api/v1/platform").status()).isEqualTo(200);
        for (int i = 0; i < 5; i++) {
            assertThat(first.get("/actuator/health").status()).isEqualTo(200);
        }
    }
}
