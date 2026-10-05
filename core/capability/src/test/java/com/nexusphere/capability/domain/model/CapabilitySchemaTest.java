package com.nexusphere.capability.domain.model;

import com.nexusphere.shared.error.DomainException;
import com.nexusphere.shared.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilitySchemaTest {

    static final Map<String, Object> CNC = Map.of(
            "type", "object",
            "required", List.of("material", "maxPartSizeMm"),
            "additionalProperties", false,
            "properties", Map.of(
                    "material", Map.of("type", "string", "enum", List.of("steel", "aluminium")),
                    "maxPartSizeMm", Map.of("type", "integer"),
                    "tolerance", Map.of("type", "number"),
                    "certifications", Map.of("type", "array", "items", Map.of("type", "string"))));

    @Test
    void acceptsAMatchingSpecification() {
        CapabilitySchema schema = CapabilitySchema.of(CNC);

        assertThat(schema.violations(Map.of("material", "steel", "maxPartSizeMm", 500, "tolerance", 0.01,
                "certifications", List.of("ISO 9001")))).isEmpty();
    }

    @Test
    void reportsEveryViolationWithItsPath() {
        CapabilitySchema schema = CapabilitySchema.of(CNC);

        List<String> violations = schema.violations(Map.of("material", "wood", "maxPartSizeMm", 12.5,
                "certifications", List.of("ISO 9001", 7), "colour", "red"));

        assertThat(violations).containsExactlyInAnyOrder(
                "$.material must be one of [steel, aluminium]",
                "$.maxPartSizeMm must be of type integer",
                "$.certifications[1] must be of type string",
                "$.colour is not allowed");
    }

    @Test
    void missingRequiredPropertiesAreViolations() {
        assertThatThrownBy(() -> CapabilitySchema.of(CNC).validate(Map.of("material", "steel")))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "CAPABILITY_SPECIFICATION_INVALID")
                .satisfies(e -> assertThat(((DomainException) e).details().get("violations"))
                        .isEqualTo(List.of("$.maxPartSizeMm is required")));
    }

    @Test
    void wholeDecimalsCountAsIntegers() {
        CapabilitySchema schema = CapabilitySchema.of(Map.of("type", "object",
                "properties", Map.of("count", Map.of("type", "integer"))));

        assertThat(schema.violations(Map.of("count", 3.0))).isEmpty();
    }

    @Test
    void rejectsInvalidSchemas() {
        assertThatThrownBy(() -> CapabilitySchema.of(Map.of("type", "string")))
                .isInstanceOf(ValidationException.class)
                .hasFieldOrPropertyWithValue("code", "CAPABILITY_SCHEMA_INVALID");
        assertThatThrownBy(() -> CapabilitySchema.of(Map.of("type", "object", "pattern", "x")))
                .hasMessageContaining("unsupported keyword pattern");
        assertThatThrownBy(() -> CapabilitySchema.of(Map.of("type", "object", "required", List.of("a"))))
                .hasMessageContaining("undeclared property a");
        assertThatThrownBy(() -> CapabilitySchema.of(Map.of("type", "object",
                "properties", Map.of("a", Map.of("type", "date"))))).hasMessageContaining("$.a.type");
        assertThatThrownBy(() -> CapabilitySchema.of(Map.of()))
                .hasFieldOrPropertyWithValue("code", "CAPABILITY_SCHEMA_INVALID");
    }
}
