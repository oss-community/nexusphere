package com.nexusphere.ledger.e2e.transparency;

import com.nexusphere.ledger.chain.LogCheckpoint;
import com.nexusphere.ledger.chain.MerkleTree;
import com.nexusphere.ledger.chain.NoteKey;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WitnessE2ETest {

    private static final KeyPair LOG_KEYS = SigningKeys.generate();
    private static final KeyPair WITNESS_KEYS = SigningKeys.generate();
    private static final int LOG_PORT = freePort();
    private static final int WITNESS_PORT = freePort();
    private static final String LOG_ORIGIN = "localhost:" + LOG_PORT;
    private static final String WITNESS_ORIGIN = "localhost:" + WITNESS_PORT;
    private static final NoteKey LOG_KEY = new NoteKey(LOG_ORIGIN, NoteKey.ED25519, LOG_KEYS.getPublic());
    private static final NoteKey WITNESS_KEY = new NoteKey(WITNESS_ORIGIN, NoteKey.COSIGNATURE,
            WITNESS_KEYS.getPublic());

    private static PostgreSQLContainer logDatabase;
    private static PostgreSQLContainer witnessDatabase;
    private static StandaloneLedger log;
    private static StandaloneLedger witness;

    @BeforeAll
    static void start() {
        logDatabase = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        witnessDatabase = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        logDatabase.start();
        witnessDatabase.start();
        witness = StandaloneLedger.start(witnessDatabase, WITNESS_PORT, WITNESS_KEYS,
                "--ledger.log.watched.acme.key=" + LOG_KEY.vkey());
        log = StandaloneLedger.start(logDatabase, LOG_PORT, LOG_KEYS,
                "--ledger.log.witnesses.partner.url=http://localhost:" + WITNESS_PORT + "/public/v1/witness",
                "--ledger.log.witnesses.partner.key=" + WITNESS_KEY.vkey());
    }

    @AfterAll
    static void stop() {
        log.close();
        witness.close();
        logDatabase.stop();
        witnessDatabase.stop();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> toolCall(String agentId, String tool) {
        Map<String, Object> body = new HashMap<>();
        body.put("agentId", agentId);
        body.put("principalId", "alice");
        body.put("action", "tools/call");
        body.put("target", tool);
        body.put("decision", "ALLOW");
        body.put("outcome", "SUCCEEDED");
        return body;
    }

    private static LogCheckpoint.Note published() {
        return LogCheckpoint.parse(log.client().withApiKey(null).get("/public/v1/log/checkpoint").body());
    }

    @Test
    void aWitnessCosignsEveryCheckpointThatExtendsWhatItSaw() {
        String agent = "agent-" + UUID.randomUUID();
        log.client().recordEvidence(toolCall(agent, "read_file"));
        log.client().post("/api/v1/checkpoints");
        LogCheckpoint.Note first = published();
        log.client().recordEvidence(toolCall(agent, "send_email"));
        log.client().post("/api/v1/checkpoints");
        LogCheckpoint.Note second = published();

        NoteKey advertised = NoteKey.parse(witness.client().withApiKey(null).get("/public/v1/witness/key").body());

        assertThat(advertised.vkey()).isEqualTo(WITNESS_KEY.vkey());
        assertThat(first.cosignedBy(WITNESS_KEY)).isPresent();
        assertThat(second.checkpoint().size()).isGreaterThan(first.checkpoint().size());
        assertThat(second.cosignedBy(WITNESS_KEY)).isPresent();
        assertThat(second.signedBy(LOG_KEY)).isTrue();

        JsonNode pkg = log.client().post("/api/v1/packages", LedgerClient.json(Map.of("agentId", agent))).json();
        PackageReport report = PackageVerifier.verify(pkg, SigningKeys.encode(LOG_KEYS.getPublic()),
                List.of(WITNESS_KEY), 1);

        assertThat(report.valid()).isTrue();
        assertThat(report.witnesses()).containsExactly(WITNESS_ORIGIN);
    }

    @Test
    void aWitnessRefusesAForkedOrStaleCheckpoint() {
        log.client().recordEvidence(toolCall("agent-" + UUID.randomUUID(), "read_file"));
        log.client().post("/api/v1/checkpoints");
        LogCheckpoint.Note seen = published();
        LedgerClient client = witness.client().withApiKey(null);
        LogCheckpoint.Note fork = new LogCheckpoint(LOG_ORIGIN, seen.checkpoint().size() + 1,
                MerkleTree.leafHash(new byte[]{9})).sign(LOG_KEY, LOG_KEYS.getPrivate());

        LedgerClient.Response stale = client.postText("/public/v1/witness/add-checkpoint",
                "old 0\n\n" + fork.text());
        LedgerClient.Response forked = client.postText("/public/v1/witness/add-checkpoint",
                "old " + seen.checkpoint().size() + "\n\n" + fork.text());
        NoteKey stranger = new NoteKey(LOG_ORIGIN, NoteKey.ED25519, SigningKeys.generate().getPublic());
        LedgerClient.Response unsigned = client.postText("/public/v1/witness/add-checkpoint",
                "old " + seen.checkpoint().size() + "\n\n" + fork.checkpoint().sign(stranger,
                        SigningKeys.generate().getPrivate()).text());

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().strip()).isEqualTo(Long.toString(seen.checkpoint().size()));
        assertThat(forked.status()).isEqualTo(422);
        assertThat(unsigned.status()).isEqualTo(403);
    }
}
