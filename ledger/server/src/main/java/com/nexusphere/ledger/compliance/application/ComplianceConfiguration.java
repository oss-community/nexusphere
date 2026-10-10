package com.nexusphere.ledger.compliance.application;

import com.nexusphere.ledger.server.config.LedgerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;

@Configuration(proxyBeanMethods = false)
class ComplianceConfiguration {

    @Bean
    Compliance compliance(LedgerProperties properties, JsonMapper json) {
        LedgerProperties.Compliance settings = properties.compliance();
        List<String> ids = settings == null ? List.of() : settings.profiles();
        String directory = settings == null ? null : settings.profileDirectory();
        String region = settings == null ? null : settings.region();
        Path path = directory == null || directory.isBlank() ? null : Path.of(directory);
        return new Compliance(ComplianceProfiles.select(ComplianceProfiles.available(json, path), ids), region);
    }
}
