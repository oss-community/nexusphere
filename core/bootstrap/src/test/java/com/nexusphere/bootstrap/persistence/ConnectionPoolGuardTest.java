package com.nexusphere.bootstrap.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionPoolGuardTest {

    private static ConnectionPoolGuard guard(int poolSize, String threads) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setMaximumPoolSize(poolSize);
        return new ConnectionPoolGuard(dataSource, new MockEnvironment()
                .withProperty("server.tomcat.threads.max", threads));
    }

    @Test
    void thePoolMustLeaveAConnectionForTheSecondConnectionOfARequest() {
        assertThatThrownBy(() -> guard(20, "20").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 21");
        assertThatCode(() -> guard(21, "20").afterPropertiesSet()).doesNotThrowAnyException();
    }
}
