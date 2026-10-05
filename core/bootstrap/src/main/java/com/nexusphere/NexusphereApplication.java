package com.nexusphere;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(systemName = "Nexusphere Core", sharedModules = "shared")
public class NexusphereApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusphereApplication.class, args);
    }
}
