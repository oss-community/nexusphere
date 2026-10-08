package com.nexusphere.ledger.e2e.support;

import com.nexusphere.ledger.LedgerApplication;
import com.nexusphere.ledger.chain.SigningKeys;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

public final class StandaloneLedger implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final String baseUrl;

    private StandaloneLedger(ConfigurableApplicationContext context, String baseUrl) {
        this.context = context;
        this.baseUrl = baseUrl;
    }

    public static StandaloneLedger start(PostgreSQLContainer postgres, int port, KeyPair keys, String... extra) {
        String baseUrl = "http://localhost:" + port;
        List<String> args = new ArrayList<>(List.of("--server.port=" + port,
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--ledger.signing.private-key=" + SigningKeys.encode(keys.getPrivate()),
                "--ledger.signing.public-key=" + SigningKeys.encode(keys.getPublic()),
                "--ledger.mandate.issuer=" + baseUrl,
                "--ledger.checkpoint.interval=1h"));
        args.addAll(List.of(extra));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(LedgerApplication.class)
                .profiles("postgresql", "dev")
                .run(args.toArray(String[]::new));
        return new StandaloneLedger(context, baseUrl);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public LedgerClient client() {
        return new LedgerClient(baseUrl, LedgerClient.DEVELOPMENT_API_KEY);
    }

    @Override
    public void close() {
        context.close();
    }
}
