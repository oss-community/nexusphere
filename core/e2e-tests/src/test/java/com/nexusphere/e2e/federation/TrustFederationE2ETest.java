package com.nexusphere.e2e.federation;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import com.nexusphere.e2e.support.IdentityApi;
import com.nexusphere.e2e.support.SovereigntyApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.nexusphere.e2e.support.FederationApi.active;
import static com.nexusphere.e2e.support.FederationApi.federations;
import static com.nexusphere.e2e.support.FederationApi.propose;
import static com.nexusphere.e2e.support.FederationApi.proposed;
import static com.nexusphere.e2e.support.FederationApi.transition;
import static com.nexusphere.e2e.support.FederationApi.trustNetwork;
import static com.nexusphere.e2e.support.FederationApi.trustRelationships;
import static com.nexusphere.e2e.support.FederationApi.trusted;
import static org.assertj.core.api.Assertions.assertThat;

class TrustFederationE2ETest extends E2ETestBase {

    private SovereigntyApi sovereignty;
    private IdentityApi identities;
    private String networkA;
    private String networkB;
    private ApiClient adminA;
    private ApiClient adminB;
    private ApiClient memberA;

    @BeforeEach
    void networks() {
        sovereignty = new SovereigntyApi(api());
        identities = new IdentityApi(api());
        networkA = sovereignty.activeNetwork("Trust A");
        networkB = sovereignty.activeNetwork("Trust B");
        adminA = administrator(networkA, "Admin A");
        adminB = administrator(networkB, "Admin B");
        String member = identities.human("Member A");
        identities.member(networkA, member);
        memberA = identities.as(identities.actor(member), networkA);
    }

    private ApiClient administrator(String networkId, String name) {
        String identity = identities.human(name);
        identities.administrator(networkId, identity);
        return identities.as(identities.actor(identity), networkId);
    }

    @Test
    @DisplayName("E2E-SC09-01 trust A to B with scope capability:discover does not make A trusted by B")
    void trustIsDirectional() {
        ApiClient.Response established = trustNetwork(adminA, networkA, networkB, "\"capability:discover\"", null);

        assertThat(established.status()).as(established.body()).isEqualTo(201);
        assertThat(established.json().path("source").path("id").asString()).isEqualTo(networkA);
        assertThat(established.json().path("effective").asBoolean()).isTrue();
        assertThat(trusted(adminA, networkA, networkA, networkB, "capability:discover")).isTrue();
        assertThat(trusted(adminA, networkA, networkA, networkB, "transaction:initiate")).isFalse();
        assertThat(trusted(adminB, networkB, networkB, networkA, "capability:discover")).isFalse();
        assertThat(trusted(adminB, networkB, networkA, networkB, "capability:discover")).isTrue();
        assertThat(adminB.get(trustRelationships(networkB) + "?direction=INCOMING").json().valueStream()
                .map(trust -> trust.path("id").asString()))
                .containsExactly(established.json().path("id").asString());
        assertThat(adminB.get(trustRelationships(networkB) + "?direction=OUTGOING").json().valueStream()).isEmpty();

        ApiClient.Response duplicate = trustNetwork(adminA, networkA, networkB, "\"agreement:propose\"", null);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.json().path("code").asString()).isEqualTo("TRUST_ALREADY_ESTABLISHED");
        ApiClient.Response byMember = trustNetwork(memberA, networkA, networkB, "\"capability:discover\"", null);
        assertThat(byMember.status()).isEqualTo(403);
        assertThat(byMember.json().path("code").asString()).isEqualTo("NETWORK_ADMINISTRATOR_REQUIRED");

        String trustId = established.json().path("id").asString();
        ApiClient.Response revokedByTarget = adminB.post(trustRelationships(networkB) + "/" + trustId + "/revoke", "");
        assertThat(revokedByTarget.status()).isEqualTo(403);
        ApiClient.Response revoked = adminA.post(trustRelationships(networkA) + "/" + trustId + "/revoke", "");
        assertThat(revoked.json().path("status").asString()).isEqualTo("REVOKED");
        assertThat(trusted(adminA, networkA, networkA, networkB, "capability:discover")).isFalse();
    }

    @Test
    @DisplayName("E2E-SC09-02 trust with effectiveUntil stops applying after the clock passes it")
    void trustExpires() throws InterruptedException {
        Instant until = Instant.now().plus(Duration.ofSeconds(2));
        ApiClient.Response established = trustNetwork(adminA, networkA, networkB, "\"capability:discover\"",
                until.toString());
        assertThat(established.status()).as(established.body()).isEqualTo(201);
        assertThat(trusted(adminA, networkA, networkA, networkB, "capability:discover")).isTrue();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        while (trusted(adminA, networkA, networkA, networkB, "capability:discover") && Instant.now().isBefore(deadline)) {
            Thread.sleep(250);
        }

        assertThat(Instant.now()).isAfterOrEqualTo(until);
        assertThat(trusted(adminA, networkA, networkA, networkB, "capability:discover")).isFalse();
        ApiClient.Response expired = adminA.get(trustRelationships(networkA) + "/" + established.json().path("id").asString());
        assertThat(expired.json().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(expired.json().path("effective").asBoolean()).isFalse();
        assertThat(trustNetwork(adminA, networkA, networkB, "\"capability:discover\"", null).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("E2E-SC09-03 federation goes PROPOSED, PENDING_ACCEPTANCE, ACTIVE and rejection is terminal")
    void federationLifecycle() {
        String federation = proposed(adminA, networkA, networkB);
        ApiClient.Response seenByPartner = adminB.get(federations(networkB) + "/" + federation);
        assertThat(seenByPartner.json().path("status").asString()).isEqualTo("PROPOSED");
        assertThat(seenByPartner.json().path("scopes").valueStream().map(scope -> scope.asString()))
                .containsExactlyInAnyOrder("CAPABILITY_DISCOVERY", "AGREEMENT_CREATION");
        ApiClient.Response early = transition(adminB, networkB, federation, "accept");
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.json().path("code").asString()).isEqualTo("FEDERATION_INVALID_TRANSITION");

        ApiClient.Response submitted = transition(adminA, networkA, federation, "submit");
        assertThat(submitted.json().path("status").asString()).isEqualTo("PENDING_ACCEPTANCE");
        ApiClient.Response selfAccept = transition(adminA, networkA, federation, "accept");
        assertThat(selfAccept.status()).isEqualTo(403);
        assertThat(selfAccept.json().path("code").asString()).isEqualTo("FEDERATION_WRONG_PARTY");
        ApiClient.Response accepted = transition(adminB, networkB, federation, "accept");
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.json().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(accepted.json().path("active").asBoolean()).isTrue();
        assertThat(adminA.get(federations(networkA) + "/" + federation).json().path("status").asString())
                .isEqualTo("ACTIVE");

        String networkC = sovereignty.activeNetwork("Trust C");
        ApiClient adminC = administrator(networkC, "Admin C");
        String declined = proposed(adminA, networkA, networkC);
        transition(adminA, networkA, declined, "submit");
        ApiClient.Response rejected = transition(adminC, networkC, declined, "reject");
        assertThat(rejected.json().path("status").asString()).isEqualTo("REJECTED");
        assertThat(transition(adminC, networkC, declined, "accept").status()).isEqualTo(409);
        assertThat(transition(adminA, networkA, declined, "terminate").status()).isEqualTo(409);
        assertThat(propose(adminA, networkA, networkC, "\"CAPABILITY_DISCOVERY\"").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("E2E-SC09-04 suspend and resume by the suspending network; terminate is final")
    void suspendResumeTerminate() {
        String federation = active(adminA, networkA, adminB, networkB);

        ApiClient.Response suspended = transition(adminB, networkB, federation, "suspend");
        assertThat(suspended.json().path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(suspended.json().path("active").asBoolean()).isFalse();
        assertThat(suspended.json().path("suspendedBy").asString()).isEqualTo(networkB);
        assertThat(transition(adminA, networkA, federation, "resume").status()).isEqualTo(403);
        ApiClient.Response resumed = transition(adminB, networkB, federation, "resume");
        assertThat(resumed.json().path("status").asString()).isEqualTo("ACTIVE");

        ApiClient.Response terminated = transition(adminA, networkA, federation, "terminate");
        assertThat(terminated.json().path("status").asString()).isEqualTo("TERMINATED");
        ApiClient.Response afterwards = transition(adminB, networkB, federation, "resume");
        assertThat(afterwards.status()).isEqualTo(409);
        assertThat(afterwards.json().path("code").asString()).isEqualTo("FEDERATION_INVALID_TRANSITION");
        assertThat(proposed(adminB, networkB, networkA)).isNotBlank();
    }

    @Test
    @DisplayName("E2E-SC09-05 only network administrators can propose or accept a federation")
    void onlyAdministratorsManageFederations() {
        ApiClient.Response principal = adminA.get("/api/v1/principal");
        assertThat(principal.json().path("networkAdministrator").asBoolean()).isTrue();
        assertThat(memberA.get("/api/v1/principal").json().path("networkAdministrator").asBoolean()).isFalse();

        ApiClient.Response byMember = propose(memberA, networkA, networkB, "\"CAPABILITY_DISCOVERY\"");
        assertThat(byMember.status()).isEqualTo(403);
        assertThat(byMember.json().path("code").asString()).isEqualTo("NETWORK_ADMINISTRATOR_REQUIRED");

        String federation = proposed(adminA, networkA, networkB);
        transition(adminA, networkA, federation, "submit");
        String member = identities.human("Member B");
        identities.member(networkB, member);
        ApiClient memberB = identities.as(identities.actor(member), networkB);
        ApiClient.Response acceptedByMember = transition(memberB, networkB, federation, "accept");
        assertThat(acceptedByMember.status()).isEqualTo(403);
        assertThat(acceptedByMember.json().path("code").asString()).isEqualTo("NETWORK_ADMINISTRATOR_REQUIRED");
        assertThat(memberB.get(federations(networkB) + "/" + federation).status()).isEqualTo(200);

        String networkC = sovereignty.activeNetwork("Trust outsider");
        ApiClient adminC = administrator(networkC, "Admin outsider");
        assertThat(adminC.get(federations(networkC) + "/" + federation).status()).isEqualTo(404);
        assertThat(transition(adminC, networkC, federation, "accept").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("E2E-SC09-06 a second open federation between the same pair and a stale version are 409")
    void conflictsAreRejected() {
        active(adminA, networkA, adminB, networkB);

        ApiClient.Response second = propose(adminA, networkA, networkB, "\"CAPABILITY_DISCOVERY\"");
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.json().path("code").asString()).isEqualTo("FEDERATION_ALREADY_EXISTS");
        assertThat(propose(adminB, networkB, networkA, "\"CAPABILITY_DISCOVERY\"").status()).isEqualTo(409);

        String networkD = sovereignty.activeNetwork("Trust D");
        ApiClient adminD = administrator(networkD, "Admin D");
        String federation = proposed(adminA, networkA, networkD);
        long proposedVersion = adminA.get(federations(networkA) + "/" + federation).json().path("version").asLong();
        ApiClient.Response submitted = transition(adminA, networkA, federation, "submit", proposedVersion);
        long submittedVersion = submitted.json().path("version").asLong();
        assertThat(submittedVersion).isGreaterThan(proposedVersion);

        ApiClient.Response stale = transition(adminD, networkD, federation, "accept", proposedVersion);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.json().path("code").asString()).isEqualTo("FEDERATION_VERSION_MISMATCH");
        assertThat(transition(adminD, networkD, federation, "accept", submittedVersion).status()).isEqualTo(200);
    }
}
