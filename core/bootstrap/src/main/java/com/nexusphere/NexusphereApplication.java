package com.nexusphere;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

/**
 * The one Nexusphere Core application. It lives in the root package so that Spring Modulith
 * treats every {@code com.nexusphere.<module>} package as an application module.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(systemName = "Nexusphere Core", sharedModules = "shared")
public class NexusphereApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusphereApplication.class, args);
    }
}
