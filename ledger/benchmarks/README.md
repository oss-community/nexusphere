# <p align="center">Nexusphere Ledger Benchmarks</p>

<p align="center">Repeatable load scenarios against a running ledger, and the published results.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Scenarios](#scenarios)
* [Run](#run)
* [Results](#results)
* [Dependencies](#dependencies)

## Purpose

<p style="text-align: justify;">

Before an operator puts a ledger in front of agents, they need to know what it costs per call and where its limit
is. The benchmark is a small Java program that drives a running ledger over its public API, so the same numbers can
be measured on any machine and compared with the ones below.

</p>

## Scenarios

| Scenario  | Request                                                     | What it measures                                |
|-----------|-------------------------------------------------------------|-------------------------------------------------|
| `record`  | `POST /api/v1/evidence` with the agent's key                | Appending one entry to the chain and the log    |
| `decide`  | `POST /api/v1/decisions` covered by a grant                 | A decision, which is also recorded as evidence  |
| `gateway` | `tools/call` through `/mcp/{name}` to the demo MCP server   | Decision, forwarding and the recorded outcome   |
| `package` | `POST /api/v1/packages` over the last 100, 1,000 and 10,000 | Exporting a package with proofs, statements and |
|           | entries, after a checkpoint                                 | receipts for every disclosed entry              |

<p style="text-align: justify;">

Each load scenario registers its own agent and grant, warms up for `--warmup` seconds, then runs `--concurrency`
clients in a closed loop for `--duration` seconds and reports the requests, the errors, the throughput and the
latency percentiles. The package scenario signs a checkpoint, records exactly the number of entries to export and
reports the median of three exports. `--json` also writes the report to a file. The exit code is `1` when any request
failed.

</p>

## Run

Step 1. Start PostgreSQL and build everything:

```shell
docker compose --file ledger/compose.yaml --project-name ledger up -d ledger-postgresql
mvn install -DskipTests=true
```

Step 2. Start the demo MCP server and a ledger with the rate limits raised and the demo server named `demo`:

```shell
java -jar ledger/demo-mcp/target/demo-mcp-1.0.0-SNAPSHOT-exec.jar > demo-mcp.log 2>&1 &
LEDGER_PROFILES=postgresql,dev LEDGER_DATABASE_PORT=5433 LEDGER_RATE_LIMIT_PER_MINUTE=10000000 LEDGER_RATE_LIMIT_BURST=100000 \
  LEDGER_MCP_SERVERS_DEMO_URL=http://localhost:8091/mcp java -jar ledger/server/target/server-1.0.0-SNAPSHOT-exec.jar > ledger.log 2>&1 &
until curl -s http://localhost:8090/actuator/health | grep -q UP; do sleep 1; done
```

Step 3. Run the load scenarios with 16 clients; the Errors column must be `0` and the exit code `0`:

```shell
java -jar ledger/benchmarks/target/benchmarks-1.0.0-SNAPSHOT-exec.jar --url http://localhost:8090 --api-key nexusphere-ledger-development-key-change-me --concurrency 16 --scenarios record,decide,gateway --json bench-16.json; echo "exit $?"
```

```text
Nexusphere Ledger benchmark against http://localhost:8090, 16 clients, 30 s per scenario
Scenario     Requests   Errors Per second    p50 ms    p95 ms    p99 ms    Max ms
record           9959        0      331.5     47.10     73.95     94.35    197.55
decide           7375        0      245.3     46.73    174.01    299.44    818.65
gateway          3698        0      122.8    106.40    271.50    387.00    844.54
exit 0
```

Step 4. Run the package scenario:

```shell
java -jar ledger/benchmarks/target/benchmarks-1.0.0-SNAPSHOT-exec.jar --url http://localhost:8090 --api-key nexusphere-ledger-development-key-change-me --scenarios package --package-sizes 100,1000,10000
```

## Results

<p style="text-align: justify;">

Measured on 2026-10-11 on one machine with 4 vCPU (Intel Xeon at 2.10 GHz) and 15 GB of memory, running OpenJDK
21.0.11, one ledger instance with a local Ed25519 key, PostgreSQL 18 in Docker and the demo MCP server, all on the
same host. Each run lasted 30 seconds after a 10-second warm-up. The full report is in
[results/2026-10-11.json](results/2026-10-11.json).

</p>

| Scenario  | Clients | Per second | p50 ms | p95 ms | p99 ms |
|-----------|---------|------------|--------|--------|--------|
| `record`  | 1       | 257.7      | 3.47   | 6.10   | 9.82   |
| `record`  | 16      | 331.5      | 47.10  | 73.95  | 94.35  |
| `record`  | 64      | 322.5      | 192.83 | 326.58 | 397.73 |
| `decide`  | 1       | 218.4      | 4.20   | 6.60   | 9.98   |
| `decide`  | 16      | 245.3      | 46.73  | 174.01 | 299.44 |
| `decide`  | 64      | 233.3      | 252.02 | 490.54 | 621.70 |
| `gateway` | 1       | 87.5       | 10.65  | 16.55  | 21.58  |
| `gateway` | 16      | 122.8      | 106.40 | 271.50 | 387.00 |
| `gateway` | 64      | 121.4      | 506.77 | 779.24 | 916.62 |

| Package entries | Median ms | Size    |
|-----------------|-----------|---------|
| 100             | 229       | 0.3 MB  |
| 1,000           | 1,845     | 3.5 MB  |
| 10,000          | 18,107    | 36.3 MB |

<p style="text-align: justify;">

Every entry takes the lock on the chain head, so appends run one after another and the ledger reached about 330
entries a second on this machine whatever the number of clients; more clients only wait longer. A decision is one
entry, and a gateway call is two, the decision and its outcome, so their ceilings follow from the same limit. One
client sees the cost of a single call: about 3.5 ms to record, 4 ms to decide and 11 ms through the gateway. Several
instances, as in [High Availability](../README.md#high-availability), share one chain and therefore the same append
limit; they add availability, not write throughput. A package costs about 1.8 ms and 3.6 KB per disclosed entry,
because each one carries its inclusion proof, its SCITT statement and its receipt.

</p>

## Dependencies

| Depends on | Used by |
|------------|---------|
| none       | none    |

##

**<p align="center">[Top](#nexusphere-ledger-benchmarks)</p>**
