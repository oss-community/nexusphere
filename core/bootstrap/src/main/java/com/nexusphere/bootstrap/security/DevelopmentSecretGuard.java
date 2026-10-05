package com.nexusphere.bootstrap.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class DevelopmentSecretGuard implements InitializingBean {

    static final String DEVELOPMENT_PROFILE = "dev";

    private static final Map<String, String> DEVELOPMENT_SECRETS = Map.of(
            "nexusphere.security.token.secret", "nexusphere-development-token-secret-change-me",
            "nexusphere.security.operator.secret", "nexusphere-development-operator-secret-change-me");

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
