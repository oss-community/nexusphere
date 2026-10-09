package com.nexusphere.ledger.server.signing;

import com.nexusphere.ledger.server.config.LedgerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.Locale;

@Configuration(proxyBeanMethods = false)
class SigningConfiguration {

    @Bean
    SigningProvider signingProvider(LedgerProperties properties, JsonMapper json) {
        LedgerProperties.Signing signing = properties.signing();
        String provider = signing == null || signing.provider() == null || signing.provider().isBlank()
                ? "local" : signing.provider().trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "local" -> new LocalSigningProvider(signing);
            case "vault" -> new VaultSigningProvider(signing.vault(), json);
            default -> throw new IllegalStateException("ledger.signing.provider must be local or vault, not "
                    + provider);
        };
    }
}
