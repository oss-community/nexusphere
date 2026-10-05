package com.nexusphere.bootstrap.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nexusphere.bootstrap.persistence.PersistenceProperties;

import java.util.List;

/** Describes the running platform. Public and read-only. */
@RestController
@RequestMapping("/api/v1/platform")
class PlatformController {

    record PlatformInfo(String name, String version, List<String> modules) {
    }

    private final ObjectProvider<BuildProperties> build;
    private final PersistenceProperties persistence;

    PlatformController(ObjectProvider<BuildProperties> build, PersistenceProperties persistence) {
        this.build = build;
        this.persistence = persistence;
    }

    @GetMapping
    PlatformInfo info() {
        BuildProperties properties = build.getIfAvailable();
        return new PlatformInfo("Nexusphere Core", properties == null ? "dev" : properties.getVersion(),
                persistence.modules());
    }
}
