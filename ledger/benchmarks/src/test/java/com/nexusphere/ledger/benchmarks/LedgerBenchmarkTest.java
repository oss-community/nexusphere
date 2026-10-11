package com.nexusphere.ledger.benchmarks;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerBenchmarkTest {

    @Test
    void percentilesUseTheNearestRank() {
        Latencies latencies = new Latencies();
        for (int i = 1; i <= 2000; i++) {
            latencies.add(i * 1_000_000L);
        }
        assertThat(latencies.count()).isEqualTo(2000);
        assertThat(latencies.percentileMillis(50)).isEqualTo(1000);
        assertThat(latencies.percentileMillis(99)).isEqualTo(1980);
        assertThat(latencies.percentileMillis(100)).isEqualTo(2000);
        assertThat(new Latencies().percentileMillis(50)).isZero();
    }

    @Test
    void optionsHaveDefaultsAndRejectUnknownScenarios() {
        LedgerBenchmark.Options options = LedgerBenchmark.Options.parse(new String[]{"--url", "http://localhost:8090/",
                "--api-key", "key", "--scenarios", "record,package", "--package-sizes", "10,20"});
        assertThat(options).isNotNull();
        assertThat(options.url()).hasToString("http://localhost:8090");
        assertThat(options.concurrency()).isEqualTo(16);
        assertThat(options.duration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.scenarios()).containsExactly("record", "package");
        assertThat(options.packageSizes()).containsExactly(10, 20);
        assertThat(LedgerBenchmark.Options.parse(new String[]{"--url", "http://x", "--api-key", "k", "--scenarios",
                "stress"})).isNull();
        assertThat(LedgerBenchmark.Options.parse(new String[]{"--api-key", "k"})).isNull();
    }

    @Test
    void aMissingLedgerFailsWithoutAReport() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LedgerBenchmark.run(new String[]{"--url", "http://127.0.0.1:1", "--api-key", "k"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertThat(code).isEqualTo(1);
        assertThat(err.toString()).startsWith("The benchmark failed");
        assertThat(LedgerBenchmark.run(new String[0], new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
    }
}
