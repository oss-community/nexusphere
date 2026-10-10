package com.nexusphere.ledger.compliance;

import com.nexusphere.ledger.compliance.application.Compliance;
import com.nexusphere.ledger.compliance.application.ComplianceProfiles;
import com.nexusphere.ledger.compliance.domain.model.ComplianceProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, ComplianceProfile> builtIn = ComplianceProfiles.available(json, null);

    private Compliance active(String region, String... ids) {
        return new Compliance(ComplianceProfiles.select(builtIn, List.of(ids)), region);
    }

    @Test
    void theBuiltInProfilesLoadAndHaveStableDigests() {
        assertThat(builtIn).containsOnlyKeys("baseline", "eu", "us");
        ComplianceProfile eu = builtIn.get("eu");
        assertThat(eu.retention()).singleElement()
                .satisfies(rule -> assertThat(rule.minimum()).isEqualTo(Period.ofMonths(6)));
        assertThat(eu.digest()).isEqualTo(ComplianceProfile.parse(json.valueToTree(eu.definition())).digest());
    }

    @Test
    void theProfileDigestMatchesTheConformanceVector() throws IOException {
        var vector = json.readTree(Path.of("../conformance/vectors/compliance-checkpoint.json").toFile());
        ComplianceProfile profile = ComplianceProfile.parse(vector.path("profile"));

        assertThat(profile.digest()).isEqualTo(vector.path("profileDigest").asString());
    }

    @Test
    void baselineIsActiveWhenNothingIsChosen() {
        Compliance compliance = new Compliance(ComplianceProfiles.select(builtIn, List.of()), null);

        assertThat(compliance.references()).extracting(p -> p.id()).containsExactly("baseline");
        assertThat(compliance.residency()).isEmpty();
        assertThat(compliance.requiredFields()).isEmpty();
    }

    @Test
    void theStrictestRuleWinsAcrossProfiles() {
        Compliance compliance = active("eea", "us", "eu", "baseline");

        assertThat(compliance.references()).extracting(p -> p.id()).containsExactly("baseline", "eu", "us");
        assertThat(compliance.retention("mcp/call")).contains(Period.ofYears(6));
        assertThat(compliance.erasureOnRequest()).isTrue();
        assertThat(compliance.erasureDeadline()).contains(Period.ofMonths(1));
        assertThat(compliance.residency()).contains(Set.of("eea"));
        assertThat(compliance.requiredFields()).containsExactly(Map.entry("inputHash", List.of("eu")),
                Map.entry("target", List.of("eu", "us")));
        assertThat(compliance.reports()).contains("dispute", "eu-ai-act-logging", "sec-17a-4-records");
    }

    @Test
    void aRestrictedRegionMustBeDeclaredAndAllowed() {
        assertThatThrownBy(() -> active(null, "eu")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEDGER_COMPLIANCE_REGION");
        assertThatThrownBy(() -> active("us", "eu")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not allowed");
        assertThat(active("EEA", "eu").region()).isEqualTo("eea");
    }

    @Test
    void anUnknownProfileIsRefused() {
        assertThatThrownBy(() -> active(null, "uk")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("There is no compliance profile uk");
    }

    @Test
    void ownProfilesAreReadFromADirectory(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("sg.json"), """
                {"id": "sg", "version": 2, "name": "Singapore",
                 "retention": [{"actions": "mcp/*", "minimum": "P5Y"}],
                 "residency": ["sg", "eea"], "requiredFields": ["attributes.model"],
                 "timestampAuthorities": ["https://tsa.example.sg"]}
                """);
        Map<String, ComplianceProfile> available = ComplianceProfiles.available(json, directory);
        Compliance compliance = new Compliance(ComplianceProfiles.select(available, List.of("sg", "eu")), "eea");

        assertThat(compliance.retention("mcp/call")).contains(Period.ofYears(5));
        assertThat(compliance.retention("key/rotate")).contains(Period.ofMonths(6));
        assertThat(compliance.residency()).contains(Set.of("eea"));
        assertThat(compliance.timestampAuthorities()).contains(Set.of("https://tsa.example.sg"));
        assertThat(compliance.requiredFields()).containsKey("attributes.model");
    }

    @Test
    void brokenProfilesAreRefusedWithTheReason(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("bad.json"), """
                {"id": "bad", "version": 1, "name": "Bad", "requiredFields": ["agentName"]}
                """);
        assertThatThrownBy(() -> ComplianceProfiles.available(json, directory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("requiredFields");

        Files.writeString(directory.resolve("bad.json"), """
                {"id": "bad", "version": 1, "name": "Bad", "retention": [{"actions": "*", "minimum": "6 months"}]}
                """);
        assertThatThrownBy(() -> ComplianceProfiles.available(json, directory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ISO-8601");

        Files.writeString(directory.resolve("bad.json"), """
                {"id": "eu", "version": 1, "name": "Copy"}
                """);
        assertThatThrownBy(() -> ComplianceProfiles.available(json, directory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("must be named eu.json");

        Files.delete(directory.resolve("bad.json"));
        Files.writeString(directory.resolve("eu.json"), """
                {"id": "eu", "version": 1, "name": "Copy"}
                """);
        assertThatThrownBy(() -> ComplianceProfiles.available(json, directory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reuses the id eu");
    }

    @Test
    void profilesWithNoCommonRegionCannotBeCombined(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("ch.json"), """
                {"id": "ch", "version": 1, "name": "Switzerland", "residency": ["ch"]}
                """);
        Map<String, ComplianceProfile> available = ComplianceProfiles.available(json, directory);

        assertThatThrownBy(() -> new Compliance(ComplianceProfiles.select(available, List.of("ch", "eu")), "ch"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no common region");
    }
}
