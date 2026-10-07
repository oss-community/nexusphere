package com.nexusphere.ledger.server.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class DevelopmentSecretGuard implements InitializingBean {

    static final String DEVELOPMENT_PROFILE = "dev";

    static final Map<String, String> DEVELOPMENT_SECRETS = Map.of(
            "ledger.security.api-key", "nexusphere-ledger-development-key-change-me",
            "ledger.signing.private-key", "MC4CAQAwBQYDK2VwBCIEIDPXxsOX77t9k2XIr5aVh1AORIeW8w4rXooP+vMeOPKL");

    private final Environment environment;

    DevelopmentSecretGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment.acceptsProfiles(Profiles.of(DEVELOPMENT_PROFILE))) {
            return;
        }
        DEVELOPMENT_SECRETS.forEach((property, secret) -> {
            if (secret.equals(environment.getProperty(property))) {
                throw new IllegalStateException(property + " uses the published development secret outside the "
                        + DEVELOPMENT_PROFILE + " profile");
            }
        });
    }
}
