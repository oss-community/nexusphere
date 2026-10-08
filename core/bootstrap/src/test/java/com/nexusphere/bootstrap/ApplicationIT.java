package com.nexusphere.bootstrap;

import com.nexusphere.NexusphereApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = NexusphereApplication.class)
@ActiveProfiles({"postgresql", "dev"})
@Import(ApplicationIT.Containers.class)
class ApplicationIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyOwningModuleHasItsOwnSchemaAndMigrationHistory() {
        List<String> expected = List.of("network", "organization", "identity", "membership", "capability", "trust",
                "federation", "authorization", "delegation", "agreement", "transaction", "audit", "integration");

        List<String> schemas = jdbc.queryForList(
                "select schema_name from information_schema.schemata", String.class);
        List<String> historyTables = jdbc.queryForList(
                "select table_schema from information_schema.tables where table_name = 'flyway_schema_history'",
                String.class);

        assertThat(schemas).containsAll(expected);
        assertThat(historyTables).containsExactlyInAnyOrderElementsOf(expected);
    }
}
