package com.nexusphere.bootstrap.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
class ConnectionPoolGuard implements InitializingBean {

    static final int CONNECTIONS_PER_REQUEST = 2;

    private final DataSource dataSource;
    private final Environment environment;

    ConnectionPoolGuard(DataSource dataSource, Environment environment) {
        this.dataSource = dataSource;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            return;
        }
        int threads = environment.getProperty("server.tomcat.threads.max", Integer.class, 200);
        int required = threads * (CONNECTIONS_PER_REQUEST - 1) + 1;
        if (hikari.getMaximumPoolSize() < required) {
            throw new IllegalStateException("spring.datasource.hikari.maximum-pool-size is "
                    + hikari.getMaximumPoolSize() + " but must be at least " + required
                    + " for server.tomcat.threads.max " + threads
                    + " because a request can hold " + CONNECTIONS_PER_REQUEST + " connections");
        }
    }
}
