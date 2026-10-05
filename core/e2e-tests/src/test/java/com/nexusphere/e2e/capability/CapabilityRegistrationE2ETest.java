package com.nexusphere.e2e.capability;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.CapabilityApi;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.SovereigntyApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.nexusphere.e2e.support.CapabilityApi.body;
import static com.nexusphere.e2e.support.CapabilityApi.capabilities;
import static com.nexusphere.e2e.support.CapabilityApi.publish;
import static com.nexusphere.e2e.support.CapabilityApi.register;
import static com.nexusphere.e2e.support.CapabilityApi.registered;
import static com.nexusphere.e2e.support.CapabilityApi.withdraw;
import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistrationE2ETest extends E2ETestBase {

    private IdentityApi identities;
    private CapabilityApi capabilityApi;
    private String network;
    private String supplier;
    private String buyer;
    private String cnc;
    private ApiClient supplierAdmin;
    private ApiClient supplierAgent;
    private ApiClient buyerMember;
    private String agent;
    private String machine;

    @BeforeEach
    void network() {
        SovereigntyApi sovereignty = new SovereigntyApi(api());
        identities = new IdentityApi(api());
        capabilityApi = new CapabilityApi(api());
        network = sovereignty.activeNetwork("Capability");
        supplier = sovereignty.organization(network, "Supplier");
        buyer = sovereignty.organization(network, "Buyer");
        cnc = capabilityApi.type(CapabilityApi.uniqueCode("manufacturing.cnc"), CapabilityApi.CNC_SCHEMA);

        String alice = identities.human("Alice");
        identities.member(network, alice, supplier);
        agent = identities.owned("AGENT", "Quoting Agent", network, supplier);
        identities.member(network, agent);
        machine = identities.owned("MACHINE", "Mill 7", network, supplier);
        identities.member(network, machine);
        String bob = identities.human("Bob");
        identities.member(network, bob, buyer);

        supplierAdmin = identities.as(identities.actor(alice), network);
        supplierAgent = identities.as(identities.actor(agent), network);
        buyerMember = identities.as(identities.actor(bob), network);
    }

    @Test
    @DisplayName("E2E-SC08-01 capabilities owned by an organization, an agent and a machine are registered and published")
    void organizationAgentAndMachinePublishCapabilities() {
        String byOrganization = registered(supplierAdmin, network, body("Precision CNC", cnc, null, null, null));
        String byAgent = registered(supplierAgent, network, body("CNC quoting", cnc, null, null, null));
        String byMachine = registered(supplierAdmin, network, body("Five-axis milling", cnc, "MACHINE", machine, null));

        ApiClient.Response draft = supplierAdmin.get(capabilities(network) + "/" + byOrganization);
        assertThat(draft.json().path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.json().path("visibility").asString()).isEqualTo("PRIVATE");
        assertThat(draft.json().path("ownerType").asString()).isEqualTo("ORGANIZATION");
        assertThat(draft.json().path("ownerId").asString()).isEqualTo(supplier);
        assertThat(draft.json().path("typeVersion").asInt()).isEqualTo(1);
        assertThat(draft.json().path("specification").path("maxPartSizeMm").asInt()).isEqualTo(500);

        assertThat(publish(supplierAdmin, network, byOrganization, "NETWORK").status()).isEqualTo(200);
        assertThat(publish(supplierAgent, network, byAgent, "NETWORK").status()).isEqualTo(200);
        ApiClient.Response machinePublished = publish(supplierAdmin, network, byMachine, "FEDERATED");
        assertThat(machinePublished.status()).isEqualTo(200);
        assertThat(machinePublished.json().path("status").asString()).isEqualTo("PUBLISHED");

        ApiClient.Response visible = buyerMember.get(capabilities(network) + "?typeCode=" + cnc);
        assertThat(visible.json().valueStream().map(capability -> capability.path("ownerType").asString()))
                .containsExactlyInAnyOrder("ORGANIZATION", "AGENT", "MACHINE");
        assertThat(visible.json().valueStream().filter(capability -> capability.path("ownerType").asString()
                .equals("AGENT")).map(capability -> capability.path("ownerId").asString())).containsExactly(agent);
        assertThat(buyerMember.get(capabilities(network) + "?ownerType=MACHINE").json().valueStream()
                .map(capability -> capability.path("id").asString())).containsExactly(byMachine);
    }

    @Test
    @DisplayName("E2E-SC08-01 a capability type gets a new version when its code is registered again")
    void capabilityTypesAreVersioned() {
        ApiClient.Response second = capabilityApi.registerType(cnc, """
                {"type":"object","properties":{"material":{"type":"string"}}}""");

        assertThat(second.status()).isEqualTo(201);
        assertThat(second.json().path("version").asInt()).isEqualTo(2);
        assertThat(api().get("/api/v1/capability-types?code=" + cnc).json().valueStream()
                .map(type -> type.path("version").asInt())).containsExactly(1, 2);
        String latest = registered(supplierAdmin, network, """
                {"name":"Any material","typeCode":"%s","specification":{"material":"wood"}}""".formatted(cnc));
        assertThat(supplierAdmin.get(capabilities(network) + "/" + latest).json().path("typeVersion").asInt())
                .isEqualTo(2);
        ApiClient.Response pinned = register(supplierAdmin, network, """
                {"name":"Pinned","typeCode":"%s","typeVersion":1,"specification":{"material":"wood"}}"""
                .formatted(cnc));
        assertThat(pinned.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("E2E-SC08-02 a specification that does not match the capability type schema is 400")
    void specificationMustMatchTheSchema() {
        ApiClient.Response invalid = register(supplierAdmin, network, """
                {"name":"Wood CNC","typeCode":"%s","specification":{"material":"wood","colour":"red"}}"""
                .formatted(cnc));

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.json().path("code").asString()).isEqualTo("CAPABILITY_SPECIFICATION_INVALID");
        assertThat(invalid.json().path("details").path("violations").valueStream().map(v -> v.asString()))
                .containsExactlyInAnyOrder("$.material must be one of [steel, aluminium]",
                        "$.maxPartSizeMm is required", "$.colour is not allowed");

        ApiClient.Response badSchema = capabilityApi.registerType(CapabilityApi.uniqueCode("bad"),
                "{\"type\":\"object\",\"required\":[\"missing\"]}");
        assertThat(badSchema.status()).isEqualTo(400);
        assertThat(badSchema.json().path("code").asString()).isEqualTo("CAPABILITY_SCHEMA_INVALID");
        ApiClient.Response unknownType = register(supplierAdmin, network,
                body("Unknown", CapabilityApi.uniqueCode("none"), null, null, null));
        assertThat(unknownType.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("E2E-SC08-03 PRIVATE is visible only to its owner and NETWORK to every member of the network")
    void visibilityIsEnforced() {
        String hidden = registered(supplierAgent, network, body("Internal pricing", cnc, null, null, "PRIVATE"));
        publish(supplierAgent, network, hidden, null);
        String shared = registered(supplierAgent, network, body("Public quoting", cnc, null, null, "NETWORK"));
        publish(supplierAgent, network, shared, null);

        assertThat(buyerMember.get(capabilities(network) + "/" + hidden).status()).isEqualTo(404);
        assertThat(buyerMember.get(capabilities(network) + "/" + shared).status()).isEqualTo(200);
        assertThat(buyerMember.get(capabilities(network)).json().valueStream()
                .map(capability -> capability.path("id").asString())).contains(shared).doesNotContain(hidden);
        assertThat(supplierAdmin.get(capabilities(network) + "/" + hidden).status()).isEqualTo(200);
        assertThat(supplierAgent.get(capabilities(network)).json().valueStream()
                .map(capability -> capability.path("id").asString())).contains(hidden, shared);

        ApiClient.Response foreignWithdraw = withdraw(buyerMember, network, shared);
        assertThat(foreignWithdraw.status()).isEqualTo(403);
        assertThat(foreignWithdraw.json().path("code").asString()).isEqualTo("CAPABILITY_OWNER_NOT_MANAGED");
        ApiClient.Response foreignOwner = register(buyerMember, network, body("Hijack", cnc, "AGENT", agent, null));
        assertThat(foreignOwner.status()).isEqualTo(403);
        assertThat(foreignOwner.json().path("code").asString()).isEqualTo("CAPABILITY_OWNER_NOT_MANAGED");
        ApiClient.Response wrongType = register(supplierAdmin, network, body("Mismatch", cnc, "MACHINE", agent, null));
        assertThat(wrongType.status()).isEqualTo(422);
        assertThat(wrongType.json().path("code").asString()).isEqualTo("CAPABILITY_OWNER_TYPE_MISMATCH");

        SovereigntyApi sovereignty = new SovereigntyApi(api());
        String other = sovereignty.activeNetwork("Capability other");
        String carol = identities.human("Carol");
        identities.member(other, carol);
        ApiClient outsider = identities.as(identities.actor(carol), other);
        assertThat(outsider.get(capabilities(network) + "/" + shared).status()).isEqualTo(403);
        assertThat(outsider.get(capabilities(other) + "/" + shared).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("E2E-SC08-04 a withdrawn capability disappears from the network catalog")
    void withdrawnCapabilitiesDisappear() {
        String capability = registered(supplierAdmin, network, body("Retiring CNC", cnc, null, null, "NETWORK"));
        publish(supplierAdmin, network, capability, null);
        assertThat(buyerMember.get(capabilities(network) + "/" + capability).status()).isEqualTo(200);

        ApiClient.Response withdrawn = withdraw(supplierAdmin, network, capability);

        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.json().path("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(buyerMember.get(capabilities(network) + "/" + capability).status()).isEqualTo(404);
        assertThat(buyerMember.get(capabilities(network)).json().valueStream()
                .map(found -> found.path("id").asString())).doesNotContain(capability);
        assertThat(supplierAdmin.get(capabilities(network) + "/" + capability).json().path("status").asString())
                .isEqualTo("WITHDRAWN");
        ApiClient.Response republish = publish(supplierAdmin, network, capability, null);
        assertThat(republish.status()).isEqualTo(409);
        assertThat(republish.json().path("code").asString()).isEqualTo("CAPABILITY_WITHDRAWN");
        assertThat(registered(supplierAdmin, network, body("Retiring CNC", cnc, null, null, null))).isNotBlank();
    }
}
