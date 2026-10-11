package com.nexusphere.ledger.server.cluster;

import com.nexusphere.ledger.server.config.LedgerProperties;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class Leadership {

    public static final long LOCK = 0x4e78734c6564676eL;

    public record Member(String instanceId, Instant startedAt, Instant heartbeatAt, boolean leader) {
    }

    private static final Logger log = LoggerFactory.getLogger(Leadership.class);

    private final DataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String instanceId;
    private final Duration heartbeat;
    private final Instant startedAt;
    private Connection connection;
    private volatile boolean leader;

    Leadership(DataSource dataSource, NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events,
               LedgerProperties properties, MeterRegistry registry, Clock clock) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
        LedgerProperties.Cluster cluster = properties.cluster();
        String configured = cluster == null ? null : cluster.instanceId();
        this.instanceId = configured == null || configured.isBlank() ? defaultInstanceId() : configured.trim();
        this.heartbeat = cluster == null || cluster.heartbeat() == null ? Duration.ofSeconds(5) : cluster.heartbeat();
        this.startedAt = clock.instant();
        Gauge.builder("ledger.leader", this, l -> l.leader ? 1 : 0)
                .description("1 on the instance that runs the scheduled work of the cluster").register(registry);
    }

    public String instanceId() {
        return instanceId;
    }

    public boolean isLeader() {
        return leader;
    }

    public List<Member> members() {
        return jdbc.query("""
                select instance_id, started_at, heartbeat_at, leader from ledger.cluster_member
                where heartbeat_at >= :since order by instance_id
                """, Map.of("since", Timestamp.from(clock.instant().minus(heartbeat.multipliedBy(3)))),
                (rs, n) -> new Member(rs.getString("instance_id"), rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("heartbeat_at").toInstant(), rs.getBoolean("leader")));
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        beat();
    }

    @Scheduled(fixedDelayString = "${ledger.cluster.heartbeat}", initialDelayString = "${ledger.cluster.heartbeat}")
    synchronized void beat() {
        boolean before = leader;
        leader = holds() || acquire();
        try {
            jdbc.update("""
                    insert into ledger.cluster_member (instance_id, started_at, heartbeat_at, leader)
                    values (:id, :startedAt, :now, :leader)
                    on conflict (instance_id) do update set heartbeat_at = :now, leader = :leader,
                        started_at = :startedAt
                    """, Map.of("id", instanceId, "startedAt", Timestamp.from(startedAt),
                    "now", Timestamp.from(clock.instant()), "leader", leader));
        } catch (RuntimeException e) {
            log.warn("The cluster heartbeat cannot be written: {}", e.getMessage());
        }
        if (leader != before) {
            log.info(leader ? "Instance {} leads the cluster" : "Instance {} no longer leads the cluster",
                    instanceId);
            events.publishEvent(new LeadershipChanged(instanceId, leader));
        }
    }

    @PreDestroy
    synchronized void release() {
        if (connection != null) {
            close();
        }
        if (leader) {
            leader = false;
            events.publishEvent(new LeadershipChanged(instanceId, false));
        }
    }

    private boolean holds() {
        if (connection == null || !leader) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                select count(*) from pg_locks
                where locktype = 'advisory' and pid = pg_backend_pid() and granted
                  and ((classid::bigint << 32) | objid::bigint) = ?
                """)) {
            statement.setLong(1, LOCK);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0;
            }
        } catch (SQLException e) {
            log.warn("The leader connection was lost: {}", e.getMessage());
            close();
            return false;
        }
    }

    private boolean acquire() {
        try {
            if (connection == null || !connection.isValid(2)) {
                close();
                connection = dataSource.getConnection();
                connection.setAutoCommit(true);
            }
            try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                statement.setLong(1, LOCK);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            log.warn("The leader lock cannot be tried: {}", e.getMessage());
            close();
            return false;
        }
    }

    private void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("Closing the leader connection failed: {}", e.getMessage());
            }
            connection = null;
        }
    }

    private static String defaultInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "ledger";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
