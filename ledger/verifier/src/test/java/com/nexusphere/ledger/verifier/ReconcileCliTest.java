package com.nexusphere.ledger.verifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcileCliTest {

    private static final JsonNode VECTOR = JsonMapper.builder().build()
            .readTree(Path.of("../conformance/vectors/a2a-reconciliation.json").toFile());

    @TempDir
    Path dir;

    private record Run(int code, String out) {
    }

    private Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = VerifierCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8));
    }

    private Path write(String name, String field) throws IOException {
        return Files.writeString(dir.resolve(name), VECTOR.path(field).asString());
    }

    @Test
    void aStatementThatNamesARequestTheOtherSideNeverSentDoesNotReconcile() throws IOException {
        Run run = run("reconcile", "--public-key", VECTOR.path("oursPublicKey").asString(), "--peer-public-key",
                VECTOR.path("theirsPublicKey").asString(), write("ours.jwt", "ours").toString(),
                write("theirs.jwt", "theirs").toString());

        assertThat(run.code()).isEqualTo(VerifierCli.INVALID);
        assertThat(run.out()).contains("Matched     : 1", "Undelivered : 1", "Result: NOT RECONCILED",
                "NOT_SENT 00000000-0000-4000-8000-000000000103");
    }

    @Test
    void aStatementSignedByAnotherKeyIsNotTrusted() throws IOException {
        Run run = run("reconcile", "--public-key", VECTOR.path("theirsPublicKey").asString(), "--peer-public-key",
                VECTOR.path("theirsPublicKey").asString(), write("ours.jwt", "ours").toString(),
                write("theirs.jwt", "theirs").toString());

        assertThat(run.code()).isEqualTo(VerifierCli.INVALID);
        assertThat(run.out()).contains("The ours statement cannot be trusted");
    }

    @Test
    void twoStatementsAreRequired() {
        assertThat(run("reconcile", "only.jwt").code()).isEqualTo(VerifierCli.USAGE);
    }
}
