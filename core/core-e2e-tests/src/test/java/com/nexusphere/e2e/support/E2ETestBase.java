package com.nexusphere.e2e.support;

import com.nexusphere.NexusphereApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = NexusphereApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"postgresql", "dev"})
@Import(E2ETestBase.Containers.class)
public abstract class E2ETestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        }
    }

    @LocalServerPort
    private int port;

    protected ApiClient api() {
        return new ApiClient("http://localhost:" + port);
    }
}
