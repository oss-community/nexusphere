package com.nexusphere.bootstrap.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * @param modules modules that own a database schema, in dependency order. Each module's
 *                migrations live in {@code classpath:db/migration/<module>} inside that module.
 */
@ConfigurationProperties("nexusphere.persistence")
public record PersistenceProperties(List<String> modules) {

    public PersistenceProperties {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }
}
