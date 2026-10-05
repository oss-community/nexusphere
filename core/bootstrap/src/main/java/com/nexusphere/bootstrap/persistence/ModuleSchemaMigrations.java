package com.nexusphere.bootstrap.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.regex.Pattern;

@Component(ModuleSchemaMigrations.BEAN_NAME)
public class ModuleSchemaMigrations implements InitializingBean {

    public static final String BEAN_NAME = "moduleSchemaMigrations";
    private static final Logger log = LoggerFactory.getLogger(ModuleSchemaMigrations.class);
    private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private final DataSource dataSource;
    private final PersistenceProperties properties;

    ModuleSchemaMigrations(DataSource dataSource, PersistenceProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        for (String module : properties.modules()) {
            if (!SCHEMA_NAME.matcher(module).matches()) {
                throw new IllegalStateException("Invalid module schema name: " + module);
            }
            MigrateResult result = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(module)
                    .defaultSchema(module)
                    .createSchemas(true)
                    .locations("classpath:db/migration/" + module)
                    .failOnMissingLocations(false)
                    .load()
                    .migrate();
            log.info("Schema {}: {} migration(s) applied, now at version {}",
                    module, result.migrationsExecuted, result.targetSchemaVersion);
        }
    }
}
