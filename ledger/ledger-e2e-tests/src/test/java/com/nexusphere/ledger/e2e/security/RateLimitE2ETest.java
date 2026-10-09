package com.nexusphere.ledger.e2e.security;

import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitE2ETest {

    private static PostgreSQLContainer postgres;
    private static StandaloneLedger ledger;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        ledger = StandaloneLedger.start(postgres, freePort(), SigningKeys.generate(),
                "--ledger.rate-limit.per-minute=6",
                "--ledger.rate-limit.burst=3");
    }

    @AfterAll
    static void stop() {
        ledger.close();
        postgres.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aCallerOverItsLimitIsToldWhenToRetry() {
        LedgerClient operator = ledger.client();
        for (int i = 0; i < 3; i++) {
            assertThat(operator.get("/api/v1/ledger/head").status()).isEqualTo(200);
        }

        LedgerClient.Response limited = operator.get("/api/v1/ledger/head");
        LedgerClient.Response other = ledger.client().withApiKey("another-key").get("/api/v1/ledger/head");
        LedgerClient.Response health = operator.get("/actuator/health");

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.json().path("code").asString()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(limited.header("Retry-After")).isPresent();
        assertThat(other.status()).isEqualTo(401);
        assertThat(health.status()).isEqualTo(200);
    }
}
