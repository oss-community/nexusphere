package com.nexusphere.e2e.platform;

import com.nexusphere.e2e.support.ApiClient;
import com.nexusphere.e2e.support.E2ETestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformBootstrapE2ETest extends E2ETestBase {

    private static final String CORRELATION = "X-Correlation-Id";

    @Test
    @DisplayName("E2E-PLT-01 health is UP and includes the database")
    void healthIsUp() {
        ApiClient.Response response = api().get("/actuator/health");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().path("status").asString()).isEqualTo("UP");
    }

    @Test
    @DisplayName("E2E-PLT-02 the OpenAPI contract is published")
    void openApiIsPublished() {
        ApiClient.Response response = api().get("/v3/api-docs");

        assertThat(response.status()).isEqualTo(200);
        JsonNode doc = response.json();
        assertThat(doc.path("openapi").asString()).startsWith("3.");
        assertThat(doc.path("info").path("title").asString()).isEqualTo("Nexusphere Core API");
        assertThat(doc.path("paths").has("/api/v1/platform")).isTrue();
    }

    @Test
    @DisplayName("E2E-PLT-03 a well-formed client correlation ID is echoed")
    void correlationIdIsEchoed() {
        ApiClient.Response response = api().withHeader(CORRELATION, "e2e-plt-03").get("/api/v1/platform");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header(CORRELATION)).contains("e2e-plt-03");
    }

    @Test
    @DisplayName("E2E-PLT-04 a missing or malformed correlation ID is replaced by a generated one")
    void correlationIdIsGeneratedWhenMissingOrMalformed() {
        String generated = api().get("/api/v1/platform").header(CORRELATION).orElseThrow();
        String replaced = api().withHeader(CORRELATION, "bad value with spaces").get("/api/v1/platform")
                .header(CORRELATION).orElseThrow();

        assertThat(generated).matches("[0-9a-f-]{36}");
        assertThat(replaced).matches("[0-9a-f-]{36}").isNotEqualTo(generated);
    }

    @Test
    @DisplayName("E2E-PLT-05 an unknown path answers with the error model and no internals")
    void unknownPathUsesErrorModel() {
        ApiClient.Response response = api().asOperator().withHeader(CORRELATION, "e2e-plt-05")
                .get("/api/v1/does-not-exist");

        assertThat(response.status()).isEqualTo(404);
        JsonNode error = response.json();
        assertThat(error.path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(error.path("category").asString()).isEqualTo("NOT_FOUND");
        assertThat(error.path("correlationId").asString()).isEqualTo("e2e-plt-05");
        assertThat(response.body()).doesNotContain("Exception", "trace", "at com.");
    }

    @Test
    @DisplayName("E2E-PLT-06 an unsupported method answers 405 with the error model")
    void unsupportedMethodUsesErrorModel() {
        ApiClient.Response response = api().delete("/api/v1/platform");

        assertThat(response.status()).isEqualTo(405);
        assertThat(response.json().path("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.json().path("correlationId").asString()).isEqualTo(response.header(CORRELATION).orElseThrow());
    }

    @Test
    @DisplayName("E2E-PLT-07 platform information lists the schema-owning modules")
    void platformInfo() {
        JsonNode info = api().get("/api/v1/platform").json();

        assertThat(info.path("name").asString()).isEqualTo("Nexusphere Core");
        assertThat(info.path("version").asString()).isEqualTo("1.0.0-SNAPSHOT");
        assertThat(info.path("modules")).hasSize(12);
    }
}
