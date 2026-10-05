package com.nexusphere.bootstrap.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI nexusphereOpenApi(ObjectProvider<BuildProperties> build) {
        String version = build.getIfAvailable() == null ? "dev" : build.getIfAvailable().getVersion();
        return new OpenAPI().info(new Info()
                .title("Nexusphere Core API")
                .version(version)
                .description("Federated trust and coordination infrastructure for human and autonomous actors."));
    }
}
