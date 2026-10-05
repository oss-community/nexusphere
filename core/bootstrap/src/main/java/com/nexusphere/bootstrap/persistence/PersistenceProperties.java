package com.nexusphere.bootstrap.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("nexusphere.persistence")
public record PersistenceProperties(List<String> modules) {

    public PersistenceProperties {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }
}
