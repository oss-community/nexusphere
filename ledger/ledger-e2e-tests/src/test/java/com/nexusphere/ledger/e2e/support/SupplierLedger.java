package com.nexusphere.ledger.e2e.support;

import com.nexusphere.ledger.LedgerApplication;
import com.nexusphere.ledger.chain.SigningKeys;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.security.KeyPair;

public final class SupplierLedger implements AutoCloseable {

    private static final KeyPair KEYS = SigningKeys.generate();

    private final PostgreSQLContainer postgres;
    private final ConfigurableApplicationContext context;
    private final String baseUrl;

    private SupplierLedger(PostgreSQLContainer postgres, ConfigurableApplicationContext context, String baseUrl) {
        this.postgres = postgres;
        this.context = context;
        this.baseUrl = baseUrl;
    }

    public static SupplierLedger start(int port, String trustedIssuer, String agentUrl) {
        PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
        String baseUrl = "http://localhost:" + port;
        ConfigurableApplicationContext context = new SpringApplicationBuilder(LedgerApplication.class)
                .profiles("postgresql", "dev")
                .run("--server.port=" + port,
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--ledger.signing.private-key=" + SigningKeys.encode(KEYS.getPrivate()),
                        "--ledger.signing.public-key=" + SigningKeys.encode(KEYS.getPublic()),
                        "--ledger.mandate.issuer=" + baseUrl,
                        "--ledger.a2a.trusted-issuers=" + trustedIssuer,
                        "--ledger.a2a.agents.sales.url=" + agentUrl,
                        "--ledger.a2a.agents.sales.authorization=" + FakeA2aAgent.AUTHORIZATION,
                        "--ledger.a2a.agents.down.url=http://localhost:1/a2a",
                        "--ledger.a2a.status-list-cache=0s",
                        "--ledger.checkpoint.interval=1h",
                        "--ledger.rate-limit.per-minute=0");
        return new SupplierLedger(postgres, context, baseUrl);
    }

    public LedgerClient client() {
        return new LedgerClient(baseUrl, LedgerClient.DEVELOPMENT_API_KEY);
    }

    @Override
    public void close() {
        context.close();
        postgres.stop();
    }
}
