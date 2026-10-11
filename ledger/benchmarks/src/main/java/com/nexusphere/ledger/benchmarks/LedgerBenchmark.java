package com.nexusphere.ledger.benchmarks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

public final class LedgerBenchmark {

    public static final String USAGE = """
            Usage: nexusphere-ledger-benchmark --url <ledger url> --api-key <operator key> [--concurrency <n>] \
            [--duration <seconds>] [--warmup <seconds>] [--scenarios record,decide,gateway,package] \
            [--mcp-server <name>] [--package-sizes <n,n>] [--json <file>]""";

    record Options(URI url, String apiKey, int concurrency, Duration duration, Duration warmup, List<String> scenarios,
                   String mcpServer, List<Integer> packageSizes, Path json) {

        static Options parse(String[] args) {
            URI url = null;
            String apiKey = null;
            int concurrency = 16;
            Duration duration = Duration.ofSeconds(30);
            Duration warmup = Duration.ofSeconds(5);
            List<String> scenarios = List.of("record", "decide", "gateway", "package");
            String mcpServer = "demo";
            List<Integer> sizes = List.of(1000, 10000);
            Path json = null;
            try {
                for (int i = 0; i < args.length; i++) {
                    switch (args[i]) {
                        case "--url" -> url = URI.create(args[++i].replaceAll("/+$", ""));
                        case "--api-key" -> apiKey = args[++i];
                        case "--concurrency" -> concurrency = Integer.parseInt(args[++i]);
                        case "--duration" -> duration = Duration.ofSeconds(Long.parseLong(args[++i]));
                        case "--warmup" -> warmup = Duration.ofSeconds(Long.parseLong(args[++i]));
                        case "--scenarios" -> scenarios = List.of(args[++i].split(","));
                        case "--mcp-server" -> mcpServer = args[++i];
                        case "--package-sizes" -> sizes = Arrays.stream(args[++i].split(",")).map(Integer::parseInt)
                                .toList();
                        case "--json" -> json = Path.of(args[++i]);
                        default -> {
                            return null;
                        }
                    }
                }
            } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
                return null;
            }
            if (url == null || apiKey == null || concurrency < 1 || duration.isZero()
                    || !List.of("record", "decide", "gateway", "package").containsAll(scenarios)) {
                return null;
            }
            return new Options(url, apiKey, concurrency, duration, warmup, scenarios, mcpServer, sizes, json);
        }
    }

    record LoadResult(String scenario, int concurrency, long requests, long errors, double perSecond, double p50,
                      double p95, double p99, double max) {
    }

    record PackageResult(int entries, double millis, long bytes) {
    }

    record Report(String startedAt, String url, int concurrency, long durationSeconds, List<LoadResult> load,
                  List<PackageResult> packages) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Options options;
    private final HttpClient http;
    private String agentId;
    private String agentKey;

    LedgerBenchmark(Options options) {
        this.options = options;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        Options options = Options.parse(args);
        if (options == null) {
            err.println(USAGE);
            return 2;
        }
        try {
            Report report = new LedgerBenchmark(options).run(out);
            if (options.json() != null) {
                Files.writeString(options.json(), JSON.writeValueAsString(report));
            }
            return report.load().stream().anyMatch(result -> result.errors() > 0) ? 1 : 0;
        } catch (IOException | IllegalStateException e) {
            err.println("The benchmark failed: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("The benchmark was interrupted");
            return 1;
        }
    }

    Report run(PrintStream out) throws IOException, InterruptedException {
        String startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        setUp();
        out.printf("Nexusphere Ledger benchmark against %s, %d clients, %d s per scenario%n", options.url(),
                options.concurrency(), options.duration().toSeconds());
        out.printf("%-10s %10s %8s %10s %9s %9s %9s %9s%n", "Scenario", "Requests", "Errors", "Per second",
                "p50 ms", "p95 ms", "p99 ms", "Max ms");
        List<LoadResult> load = new ArrayList<>();
        for (String scenario : options.scenarios()) {
            if ("package".equals(scenario)) {
                continue;
            }
            LoadResult result = load(scenario, request(scenario));
            load.add(result);
            out.printf("%-10s %10d %8d %10.1f %9.2f %9.2f %9.2f %9.2f%n", result.scenario(), result.requests(),
                    result.errors(), result.perSecond(), result.p50(), result.p95(), result.p99(), result.max());
        }
        List<PackageResult> packages = new ArrayList<>();
        if (options.scenarios().contains("package")) {
            out.printf("%-10s %10s %12s %12s%n", "Package", "Entries", "Median ms", "Bytes");
            for (int size : options.packageSizes()) {
                PackageResult result = exportPackage(size);
                packages.add(result);
                out.printf("%-10s %10d %12.1f %12d%n", "package", result.entries(), result.millis(), result.bytes());
            }
        }
        return new Report(startedAt, options.url().toString(), options.concurrency(), options.duration().toSeconds(),
                load, packages);
    }

    private void setUp() throws IOException, InterruptedException {
        agentId = "benchmark-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode agent = send("POST", "/api/v1/agents", options.apiKey(), Map.of(), Map.of("agentId", agentId,
                "name", "Benchmark agent", "ownerId", "benchmark"), 201);
        agentKey = agent.path("apiKey").asString();
        send("POST", "/api/v1/grants", options.apiKey(), Map.of(), Map.of("principalId", "benchmark-principal",
                "agentId", agentId, "actions", List.of("tools/call"),
                "targets", List.of("bench/*", options.mcpServer() + "/*"),
                "expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()), 201);
    }

    private IntFunction<HttpRequest> request(String scenario) {
        return switch (scenario) {
            case "record" -> i -> post("/api/v1/evidence", agentKey, Map.of(), Map.of("agentId", agentId,
                    "principalId", "benchmark-principal", "action", "tools/call", "target", "bench/record",
                    "decision", "ALLOW", "outcome", "SUCCEEDED", "inputHash", "0".repeat(64)));
            case "decide" -> i -> post("/api/v1/decisions", agentKey, Map.of(), Map.of(
                    "principalId", "benchmark-principal", "action", "tools/call", "target", "bench/decide"));
            case "gateway" -> i -> post("/mcp/" + options.mcpServer(), agentKey,
                    Map.of("X-Ledger-Principal", "benchmark-principal", "Accept", "application/json"),
                    Map.of("jsonrpc", "2.0", "id", i, "method", "tools/call", "params", Map.of("name", "send_email",
                            "arguments", Map.of("to", "bob@example.com", "subject", "Benchmark"))));
            default -> throw new IllegalArgumentException(scenario);
        };
    }

    private LoadResult load(String scenario, IntFunction<HttpRequest> requests) throws InterruptedException {
        drive(requests, options.warmup(), null);
        Latencies latencies = new Latencies();
        long started = System.nanoTime();
        drive(requests, options.duration(), latencies);
        double seconds = (System.nanoTime() - started) / 1e9;
        return new LoadResult(scenario, options.concurrency(), latencies.count(), latencies.errors(),
                latencies.count() / seconds, latencies.percentileMillis(50), latencies.percentileMillis(95),
                latencies.percentileMillis(99), latencies.percentileMillis(100));
    }

    private void drive(IntFunction<HttpRequest> requests, Duration duration, Latencies latencies)
            throws InterruptedException {
        if (duration.isZero()) {
            return;
        }
        long until = System.nanoTime() + duration.toNanos();
        AtomicLong counter = new AtomicLong();
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < options.concurrency(); c++) {
                clients.submit(() -> {
                    while (System.nanoTime() < until) {
                        HttpRequest request = requests.apply((int) counter.incrementAndGet());
                        long start = System.nanoTime();
                        try {
                            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                            long elapsed = System.nanoTime() - start;
                            if (failed(response)) {
                                if (latencies != null) {
                                    latencies.error();
                                }
                            } else if (latencies != null) {
                                latencies.add(elapsed);
                            }
                        } catch (IOException e) {
                            if (latencies != null) {
                                latencies.error();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                });
            }
        }
    }

    private static boolean failed(HttpResponse<String> response) {
        return response.statusCode() / 100 != 2
                || response.body().contains("\"jsonrpc\"") && response.body().contains("\"error\":");
    }

    private void fill(int count) throws InterruptedException {
        IntFunction<HttpRequest> record = request("record");
        AtomicLong remaining = new AtomicLong(count);
        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < options.concurrency(); c++) {
                clients.submit(() -> {
                    while (remaining.getAndDecrement() > 0) {
                        HttpResponse<String> response = http.send(record.apply(0), HttpResponse.BodyHandlers.ofString());
                        if (failed(response)) {
                            throw new IllegalStateException("Recording evidence answered " + response.statusCode());
                        }
                    }
                    return null;
                });
            }
        }
    }

    private PackageResult exportPackage(int size) throws IOException, InterruptedException {
        long from = send("POST", "/api/v1/checkpoints", options.apiKey(), Map.of(), Map.of(), 200)
                .path("sequence").asLong() + 1;
        fill(size);
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("agentId", agentId);
        scope.put("fromSequence", from);
        scope.put("toSequence", from + size - 1);
        double[] millis = new double[3];
        long bytes = 0;
        for (int i = 0; i < millis.length; i++) {
            long start = System.nanoTime();
            HttpResponse<byte[]> response = http.send(post("/api/v1/packages", options.apiKey(), Map.of(), scope),
                    HttpResponse.BodyHandlers.ofByteArray());
            millis[i] = (System.nanoTime() - start) / 1e6;
            if (response.statusCode() != 200 && response.statusCode() != 201) {
                throw new IllegalStateException("Exporting a package answered " + response.statusCode());
            }
            bytes = response.body().length;
        }
        Arrays.sort(millis);
        return new PackageResult(size, millis[1], bytes);
    }

    private HttpRequest post(String path, String key, Map<String, String> headers, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(options.url() + path))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        headers.forEach(builder::header);
        return builder.build();
    }

    private JsonNode send(String method, String path, String key, Map<String, String> headers, Object body,
                          int expected) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(post(path, key, headers, body),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expected) {
            throw new IllegalStateException(method + " " + path + " answered " + response.statusCode() + ": "
                    + response.body());
        }
        return JSON.readTree(response.body());
    }
}
