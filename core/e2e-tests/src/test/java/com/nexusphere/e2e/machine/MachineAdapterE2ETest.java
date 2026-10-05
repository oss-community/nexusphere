package com.nexusphere.e2e.machine;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.CapabilityApi;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.TrustedInteraction;
import com.nexusphere.e2e.support.WarehouseTransportRobot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

import static com.nexusphere.e2e.support.CapabilityApi.published;
import static com.nexusphere.e2e.support.TrustedInteraction.agreements;
import static com.nexusphere.e2e.support.TrustedInteraction.transactions;
import static org.assertj.core.api.Assertions.assertThat;

class MachineAdapterE2ETest extends E2ETestBase {

    private static final String TRANSPORT_SCHEMA = """
            {"type":"object","required":["maxPayloadKg"],"properties":{"maxPayloadKg":{"type":"integer"}}}""";

    private TrustedInteraction world;
    private String machine;
    private String machinePrincipal;
    private String transport;
    private WarehouseTransportRobot robot;

    @BeforeEach
    void machineOfOrganizationB() {
        world = TrustedInteraction.establish(api());
        machine = world.identities.owned("MACHINE", "Warehouse Transport Robot", world.networkB,
                world.organizationB);
        machinePrincipal = world.identities.member(world.networkB, machine, world.organizationB);
        String type = new CapabilityApi(api()).type(CapabilityApi.uniqueCode("autonomous.material.transport"),
                TRANSPORT_SCHEMA);
        transport = published(world.humanB, world.networkB, """
                {"name":"Pallet transport","typeCode":"%s","ownerType":"MACHINE","ownerId":"%s",
                 "visibility":"FEDERATED","specification":{"maxPayloadKg":500}}""".formatted(type, machine));
        robot = new WarehouseTransportRobot(world.identities.as(world.identities.actor(machine), world.networkB),
                world.networkB);
    }

    private String proposedTransport() {
        ApiClient.Response draft = world.draft(world.humanA, transport, "{\"pallets\":4}");
        assertThat(draft.status()).as(draft.body()).isEqualTo(201);
        String agreement = draft.json().path("id").asString();
        assertThat(world.humanA.post(agreements(world.networkA) + "/" + agreement + "/propose", "").status())
                .isEqualTo(200);
        return agreement;
    }

    private String activeTransport() {
        String agreement = proposedTransport();
        assertThat(world.humanB.post(agreements(world.networkB) + "/" + agreement + "/accept", "{\"version\":1}")
                .status()).isEqualTo(200);
        assertThat(world.humanB.post(agreements(world.networkB) + "/" + agreement + "/activate", "").status())
                .isEqualTo(200);
        return agreement;
    }

    private JsonNode requested(String agreement) {
        ApiClient.Response requested = world.humanA.post(transactions(world.networkA), "{\"agreementId\":\""
                + agreement + "\",\"type\":\"material.transport\",\"metadata\":{\"from\":\"A1\",\"to\":\"Dock 3\"}}");
        assertThat(requested.status()).as(requested.body()).isEqualTo(201);
        return requested.json();
    }

    private JsonNode audit(String transaction, String action) {
        return world.adminB.get("/api/v1/audit-events?transactionId=" + transaction).json().valueStream()
                .filter(event -> event.path("action").asString().equals(action)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("E2E-SC02-01 a transaction for a machine-owned capability completes and the audit names requester, owner, machine and result")
    void machineCompletesAnAuthorizedTransaction() {
        String agreement = activeTransport();
        String transaction = requested(agreement).path("id").asString();

        JsonNode pending = robot.pending();
        assertThat(pending.valueStream().map(task -> task.path("id").asString())).containsExactly(transaction);
        assertThat(pending.get(0).path("metadata").path("to").asString()).isEqualTo("Dock 3");
        List<JsonNode> reports = robot.work(task -> Optional.empty());

        assertThat(robot.received()).containsExactly(transaction);
        assertThat(reports.getFirst().path("status").asString()).isEqualTo("COMPLETED");
        assertThat(reports.getFirst().path("executorIdentityId").asString()).isEqualTo(machine);
        assertThat(reports.getFirst().path("result").path("dock").asString()).isEqualTo("Dock 3");
        JsonNode completed = world.humanA.get(transactions(world.networkA) + "/" + transaction).json();
        assertThat(completed.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.path("executorPrincipalId").asString()).isEqualTo(machinePrincipal);
        assertThat(robot.pending().size()).isZero();

        JsonNode request = audit(transaction, "transaction:requested");
        assertThat(request.path("principalId").asString()).isEqualTo(world.humanAPrincipal);
        assertThat(request.path("accountableOrganizationId").asString()).isEqualTo(world.organizationA);
        JsonNode execution = audit(transaction, "transaction:executing");
        assertThat(execution.path("principalId").asString()).isEqualTo(machinePrincipal);
        assertThat(execution.path("identityId").asString()).isEqualTo(machine);
        assertThat(execution.path("accountableOrganizationId").asString()).isEqualTo(world.organizationB);
        assertThat(execution.path("agreementId").asString()).isEqualTo(agreement);
        JsonNode result = audit(transaction, "transaction:completed");
        assertThat(result.path("result").asString()).isEqualTo("SUCCEEDED");
        assertThat(result.path("identityId").asString()).isEqualTo(machine);
        assertThat(result.path("occurredAt").asString()).isNotBlank();
        JsonNode allowed = world.adminB.get("/api/v1/audit-events?decisionId="
                + execution.path("decisionId").asString()).json();
        assertThat(allowed.valueStream().map(event -> event.path("action").asString() + " "
                + event.path("result").asString() + " " + event.path("reason").asString()))
                .startsWith("transaction:execute ALLOWED OWNERSHIP");

        JsonNode trail = world.adminB.get("/api/v1/audit-events/trail?transactionId=" + transaction).json();
        JsonNode capability = trail.path("chain").valueStream()
                .filter(link -> link.path("kind").asString().equals("CAPABILITY")).findFirst().orElseThrow();
        assertThat(capability.path("attributes").path("ownerType").asString()).isEqualTo("MACHINE");
        assertThat(capability.path("attributes").path("ownerId").asString()).isEqualTo(machine);
        assertThat(capability.path("attributes").path("accountableOrganizationId").asString())
                .isEqualTo(world.organizationB);
        JsonNode link = trail.path("chain").valueStream()
                .filter(found -> found.path("kind").asString().equals("TRANSACTION")).findFirst().orElseThrow();
        assertThat(link.path("attributes").path("executorIdentityId").asString()).isEqualTo(machine);
    }

    @Test
    @DisplayName("E2E-SC02-02 the simulator reports FAILED; the transaction is FAILED with a reason and the failure is audited")
    void machineReportsAFailure() {
        String transaction = requested(activeTransport()).path("id").asString();

        List<JsonNode> reports = robot.work(task -> Optional.of("Aisle 4 blocked"));

        assertThat(robot.received()).containsExactly(transaction);
        assertThat(reports.getFirst().path("status").asString()).isEqualTo("FAILED");
        assertThat(reports.getFirst().path("reason").asString()).isEqualTo("Aisle 4 blocked");
        JsonNode failed = audit(transaction, "transaction:failed");
        assertThat(failed.path("result").asString()).isEqualTo("FAILED");
        assertThat(failed.path("reason").asString()).isEqualTo("Aisle 4 blocked");
        assertThat(failed.path("principalId").asString()).isEqualTo(machinePrincipal);
        assertThat(world.adminA.get("/api/v1/audit-events?transactionId=" + transaction).json().valueStream()
                .map(event -> event.path("action").asString())).contains("transaction:failed");
    }

    @Test
    @DisplayName("E2E-SC02-03 a rejected transaction never reaches the simulator")
    void rejectedTransactionsNeverReachTheMachine() {
        JsonNode rejected = requested(proposedTransport());
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("reason").asString()).isEqualTo("AGREEMENT_NOT_ACTIVE");
        String cncTransaction = world.completedTransaction(world.activeAgreement());

        assertThat(robot.work(task -> Optional.empty())).isEmpty();
        assertThat(robot.received()).isEmpty();
        ApiClient.Response forced = robot.start(rejected.path("id").asString());
        assertThat(forced.status()).isEqualTo(404);
        ApiClient.Response foreign = robot.start(cncTransaction);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.json().path("code").asString()).isEqualTo("NOT_FOUND");

        ApiClient.Response human = world.humanB.get("/api/v1/networks/" + world.networkB + "/machine/tasks");
        assertThat(human.status()).isEqualTo(403);
        assertThat(human.json().path("code").asString()).isEqualTo("MACHINE_IDENTITY_REQUIRED");
    }

    @Test
    @DisplayName("E2E-SC02-04 a MACHINE identity without an owning organization cannot be created")
    void machinesNeedAnOwningOrganization() {
        ApiClient.Response unowned = api().post("/api/v1/identities",
                "{\"type\":\"MACHINE\",\"displayName\":\"Stray robot\"}");

        assertThat(unowned.status()).isEqualTo(400);
        assertThat(unowned.json().path("code").asString()).isEqualTo("OWNING_ORGANIZATION_REQUIRED");
    }
}
