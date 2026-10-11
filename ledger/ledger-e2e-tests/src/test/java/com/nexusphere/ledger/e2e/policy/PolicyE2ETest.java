package com.nexusphere.ledger.e2e.policy;

import com.nexusphere.ledger.chain.Hashes;
import com.nexusphere.ledger.chain.SigningKeys;
import com.nexusphere.ledger.e2e.support.LedgerClient;
import com.nexusphere.ledger.e2e.support.StandaloneLedger;
import com.nexusphere.ledger.verifier.PackageReport;
import com.nexusphere.ledger.verifier.PackageVerifier;
import com.nexusphere.ledger.verifier.VerifierCli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyE2ETest {

    private static final String POLICIES = """
            @id("no-wire-transfers")
            forbid(principal, action == Action::"tools/call", resource == Target::"bank/wire_transfer");

            @id("small-payments")
            forbid(principal, action, resource == Target::"bank/pay_invoice")
              unless { context.attributes has amount && context.attributes.amount == "100" };
            """;
    private static final String HASH = Hashes.sha256(POLICIES.getBytes(StandardCharsets.UTF_8));

    private PostgreSQLContainer postgres;
    private final KeyPair keys = SigningKeys.generate();

    @BeforeEach
    void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        postgres.start();
    }

    @AfterEach
    void stop() {
        postgres.stop();
    }

    private static void agentWithGrant(LedgerClient client) {
        assertThat(client.post("/api/v1/agents", LedgerClient.json(Map.of("agentId", "payments-agent",
                "name", "Payments agent", "ownerId", "acme"))).status()).isEqualTo(201);
        assertThat(client.post("/api/v1/grants", LedgerClient.json(Map.of("principalId", "alice",
                "agentId", "payments-agent", "actions", List.of("tools/call"), "targets", List.of("bank/*"),
                "expiresAt", Instant.now().plus(1, ChronoUnit.HOURS).toString()))).status()).isEqualTo(201);
    }

    private static JsonNode decide(LedgerClient client, String target, Map<String, String> attributes) {
        return client.post("/api/v1/decisions", LedgerClient.json(Map.of("agentId", "payments-agent",
                "principalId", "alice", "action", "tools/call", "target", target, "attributes", attributes))).json();
    }

    @Test
    void policiesForbidWhatAGrantAllowsAndTheirTextIsDisclosedAndVerified(@TempDir Path directory)
            throws IOException {
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, freePort(), keys)) {
            LedgerClient client = ledger.client();
            agentWithGrant(client);

            assertThat(client.get("/api/v1/policies").status()).isEqualTo(404);
            assertThat(decide(client, "bank/wire_transfer", Map.of()).path("decision").asString()).isEqualTo("ALLOW");

            LedgerClient.Response invalid = client.put("/api/v1/policies",
                    LedgerClient.json(Map.of("text", "forbid(principal, action")));
            assertThat(invalid.status()).isEqualTo(400);
            assertThat(invalid.json().path("details").path("fields").path("text").asString())
                    .startsWith("The policies cannot be parsed");

            JsonNode active = client.put("/api/v1/policies", LedgerClient.json(Map.of("text", POLICIES))).json();
            assertThat(active.path("hash").asString()).isEqualTo(HASH);
            assertThat(active.path("defaultEffect").asString()).isEqualTo("permit");
            assertThat(active.path("policies").toString()).isEqualTo("[\"no-wire-transfers\",\"small-payments\"]");
            assertThat(client.get("/api/v1/policies").json().path("hash").asString()).isEqualTo(HASH);
            assertThat(client.get("/api/v1/policies/" + HASH).json().path("text").asString()).isEqualTo(POLICIES);

            JsonNode wire = decide(client, "bank/wire_transfer", Map.of());
            JsonNode large = decide(client, "bank/pay_invoice", Map.of("amount", "900"));
            JsonNode small = decide(client, "bank/pay_invoice", Map.of("amount", "100"));
            assertThat(wire.path("decision").asString()).isEqualTo("DENY");
            assertThat(wire.path("reasonCode").asString()).isEqualTo("POLICY_DENIED");
            assertThat(large.path("reasonCode").asString()).isEqualTo("POLICY_DENIED");
            assertThat(small.path("decision").asString()).isEqualTo("ALLOW");

            JsonNode denied = client.get("/api/v1/evidence/" + wire.path("decisionId").asString()).json();
            assertThat(denied.path("reason").asString())
                    .isEqualTo("Policy no-wire-transfers forbids tools/call on bank/wire_transfer.");
            assertThat(denied.path("attributes").path("policy.hash").asString()).isEqualTo(HASH);
            assertThat(denied.path("attributes").path("policy.ids").asString()).isEqualTo("no-wire-transfers");
            assertThat(denied.path("attributes").path("policy.defaultEffect").asString()).isEqualTo("permit");
            JsonNode allowed = client.get("/api/v1/evidence/" + small.path("decisionId").asString()).json();
            assertThat(allowed.path("attributes").path("policy.ids").asString()).isEqualTo("ledger-default-permit");

            LedgerClient.Response forged = client.post("/api/v1/decisions", LedgerClient.json(Map.of(
                    "agentId", "payments-agent", "principalId", "alice", "action", "tools/call",
                    "target", "bank/pay_invoice", "attributes", Map.of("policy.hash", "0".repeat(64)))));
            assertThat(forged.status()).isEqualTo(400);
            assertThat(forged.json().path("details").path("fields").path("attributes").asString())
                    .isEqualTo("names starting with policy. or approval. are set by the ledger");

            client.post("/api/v1/checkpoints");
            JsonNode pkg = client.post("/api/v1/packages",
                    LedgerClient.json(Map.of("agentId", "payments-agent"))).json();
            assertThat(pkg.path("policies")).hasSize(1);
            assertThat(pkg.path("policies").get(0).path("text").asString()).isEqualTo(POLICIES);
            PackageReport report = PackageVerifier.verify(pkg, SigningKeys.encode(keys.getPublic()));
            assertThat(report.problems()).isEmpty();
            assertThat(report.disclosedPolicies()).isEqualTo(1);

            Path file = directory.resolve("package.json");
            Files.writeString(file, pkg.toString());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThat(VerifierCli.run(new String[]{"--public-key", SigningKeys.encode(keys.getPublic()),
                    file.toString()}, new PrintStream(out), System.err)).isZero();
            assertThat(out.toString()).contains("Policies    : 1 policy set texts match the hashes their entries name");

            ((ObjectNode) pkg.path("policies").get(0)).put("text", "permit(principal, action, resource);");
            assertThat(PackageVerifier.verify(pkg, SigningKeys.encode(keys.getPublic())).problems())
                    .containsExactly("policy set " + HASH + " does not match its text");
            assertThat(client.get("/api/v1/verification").json().path("valid").asBoolean()).isTrue();
        }
    }

    @Test
    void aDefaultDenyLedgerAllowsOnlyWhatAPolicyPermits() {
        try (StandaloneLedger ledger = StandaloneLedger.start(postgres, freePort(), keys,
                "--ledger.policy.default-effect=deny")) {
            LedgerClient client = ledger.client();
            agentWithGrant(client);
            client.put("/api/v1/policies", LedgerClient.json(Map.of("text", """
                    @id("pay-invoices")
                    permit(principal == Agent::"payments-agent", action, resource == Target::"bank/pay_invoice");
                    """)));

            JsonNode paid = decide(client, "bank/pay_invoice", Map.of());
            JsonNode wire = decide(client, "bank/wire_transfer", Map.of());
            JsonNode evidence = client.get("/api/v1/evidence/" + wire.path("decisionId").asString()).json();

            assertThat(paid.path("decision").asString()).isEqualTo("ALLOW");
            assertThat(wire.path("reasonCode").asString()).isEqualTo("POLICY_DENIED");
            assertThat(evidence.path("reason").asString())
                    .isEqualTo("No policy permits tools/call on bank/wire_transfer.");
            assertThat(evidence.path("attributes").path("policy.defaultEffect").asString()).isEqualTo("deny");
            assertThat(client.get("/api/v1/policies").json().path("defaultEffect").asString()).isEqualTo("deny");
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
