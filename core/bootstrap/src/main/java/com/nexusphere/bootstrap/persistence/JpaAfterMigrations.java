package com.nexusphere.bootstrap.persistence;

import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.stereotype.Component;

@Component
class JpaAfterMigrations extends EntityManagerFactoryDependsOnPostProcessor {

    JpaAfterMigrations() {
        super(ModuleSchemaMigrations.BEAN_NAME);
    }
}
