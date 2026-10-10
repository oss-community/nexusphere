package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.SigningKeys;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.security.KeyPair;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportVerifierTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ObjectNode report(ObjectNode pkg, KeyPair keys) {
        ObjectNode report = JSON.createObjectNode();
        report.put("format", ReportVerifier.FORMAT).put("report", "dispute").put("title", "Dispute package")
                .put("createdAt", "2026-10-07T09:00:00Z");
        report.putArray("profiles");
        report.putNull("subject");
        report.putObject("scope").put("agentId", "agent-a").putNull("principalId").put("fromSequence", 1)
                .put("toSequence", pkg.path("scope").path("toSequence").asLong());
        List<String> hashes = pkg.path("links").valueStream().filter(link -> link.path("entry").isObject())
                .map(link -> link.path("hash").asString()).toList();
        report.putObject("evidence").put("checkpoint", pkg.path("checkpoint").path("sequence").asLong())
                .put("headHash", pkg.path("checkpoint").path("headHash").asString()).put("entries", hashes.size())
                .put("entriesDigest", ReportVerifier.entriesDigest(hashes)).put("valid", true);
        report.put("notice", "A mapping, not a certification");
        report.putArray("sections").addObject().put("id", "timeline").put("reference", "Incident timeline")
                .put("requirement", "What the agents did").put("status", "SHOWN")
                .putArray("findings").add("#2 agent-a tools/call on search");
        report.put("keyId", SigningKeys.keyIdOf(keys.getPublic()));
        return sign(report, keys);
    }

    private static ObjectNode sign(ObjectNode report, KeyPair keys) {
        report.remove("signature");
        report.put("signature", SigningKeys.sign(keys.getPrivate(), ReportVerifier.signedBytes(report)));
        return report;
    }

    private static String pinned() {
        return SigningKeys.encode(PackageVerifierTest.KEYS.getPublic());
    }

    @Test
    void aReportSignedByTheLedgerForThePackageIsValid() {
        ObjectNode pkg = PackageVerifierTest.pkg(4, 2);

        ReportVerifier.ReportCheck check = ReportVerifier.verify(report(pkg, PackageVerifierTest.KEYS), pkg,
                pinned(), null);

        assertThat(check.problems()).isEmpty();
        assertThat(check.valid()).isTrue();
        assertThat(check.entries()).isEqualTo(1);
        assertThat(check.statuses()).containsEntry("SHOWN", 1);
    }

    @Test
    void aChangedFindingBreaksTheSignature() {
        ObjectNode pkg = PackageVerifierTest.pkg(4, 2);
        ObjectNode report = report(pkg, PackageVerifierTest.KEYS);
        ((ObjectNode) report.path("sections").get(0)).put("status", "NOT_SHOWN");

        assertThat(ReportVerifier.verify(report, pkg, pinned(), null).problems())
                .containsExactly("the signature of the report is not valid");
    }

    @Test
    void aReportForOtherEntriesIsRejected() {
        ObjectNode report = report(PackageVerifierTest.pkg(4, 2), PackageVerifierTest.KEYS);

        assertThat(ReportVerifier.verify(report, PackageVerifierTest.pkg(4, 3), pinned(), null).problems())
                .contains("the report covers other entries than the package discloses",
                        "the report is for another checkpoint than the package");
    }

    @Test
    void aReportSignedByAnotherKeyIsRejected() {
        ObjectNode pkg = PackageVerifierTest.pkg(4, 2);

        assertThat(ReportVerifier.verify(report(pkg, SigningKeys.generate()), pkg, pinned(), null).problems())
                .singleElement().asString().contains("which the package does not trust");
    }

    @Test
    void anUnknownStatusIsRejected() {
        ObjectNode pkg = PackageVerifierTest.pkg(4, 2);
        ObjectNode report = report(pkg, PackageVerifierTest.KEYS);
        ((ObjectNode) report.path("sections").get(0)).put("status", "COMPLIANT");

        assertThat(ReportVerifier.verify(sign(report, PackageVerifierTest.KEYS), pkg, pinned(), null).problems())
                .containsExactly("section timeline has an unknown status COMPLIANT");
    }
}
