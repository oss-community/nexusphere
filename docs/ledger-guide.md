# <p align="center">Ledger Guide</p>

<p align="center">Install the Nexusphere Ledger, run agents through it, and present it.</p>

## <p align="center">Table of Content</p>

* [What It Does](#what-it-does)
* [Install](#install)
* [Agents](#agents)
* [Real Agents and MCP Servers](#real-agents-and-mcp-servers)
* [Beside an Existing Gateway](#beside-an-existing-gateway)
* [Present It](#present-it)
* [Stop](#stop)
* [Next Steps](#next-steps)

## What It Does

<p style="text-align: justify;">

An agent is software that acts for a person. The ledger stands between the agent and the tools it uses. Every tool
call is checked against a grant the person gave, allowed or denied, and written to a hash chain that nobody can change
unnoticed, not even the operator. Anyone can later check what an agent did, for whom and with whose permission, with
a package of evidence and a verifier that runs offline.

</p>

| Word       | Meaning                                                                                   |
|------------|-------------------------------------------------------------------------------------------|
| Agent      | An identity with its own API key; the program that uses the key is the agent              |
| Principal  | The person or organization the agent acts for, such as `alice`                            |
| Grant      | What a principal lets an agent do: actions, targets, expiry and a use limit               |
| Decision   | ALLOW or DENY for one call, with a reason code                                            |
| Evidence   | One entry in the hash chain: who, what, for whom, the decision and the outcome            |
| Checkpoint | The chain head signed with the ledger key, so later changes are detectable                |
| Package    | The evidence of one agent or principal with the proof that it belongs to the signed chain |
| Mandate    | A signed token an agent carries to another organization to prove what it may do           |

## Install

### Prerequisites

| Tool   | Version | Check                    |
|--------|---------|--------------------------|
| Java   | 21      | `java -version`          |
| Maven  | 3.9     | `mvn -version`           |
| Docker | any     | `docker compose version` |
| jq     | any     | `jq --version`           |

### Start

Every command runs in the root of the repository.

Step 1. Build the server, the verifier and the demo MCP server:

```shell
mvn -pl ledger/server,ledger/verifier,ledger/demo-mcp -am package -DskipTests=true
```

Step 2. Start the services:

```shell
docker compose --file ledger/compose.yaml --project-name ledger up -d --build
```

Step 3. Check the ledger; the answer must be `{"status":"UP"}`:

```shell
curl http://localhost:8090/actuator/health
```

| Service      | URL                       | Sign in                                                |
|--------------|---------------------------|--------------------------------------------------------|
| Web UI       | http://localhost:5173     | Operator key, or `alice`/`alice` and `bob`/`bob`       |
| Ledger API   | http://localhost:8090     | `Authorization: Bearer {key}`                          |
| Demo MCP     | http://localhost:8091/mcp | None, reached through the ledger at `/mcp/demo`        |
| Keycloak     | http://localhost:8180     | `admin`/`admin`                                        |
| Adminer      | http://localhost:8092     | Server `ledger-postgresql`, user and password `ledger` |
| Prometheus   | http://localhost:9090     | None                                                   |
| Alertmanager | http://localhost:9093     | None                                                   |

<p style="text-align: justify;">

The development operator key is `nexusphere-ledger-development-key-change-me`. It only works with the `dev` profile;
production keys and secrets are described in [Operations](operations.md).

</p>

### Run the Demo

Step 4. Run the demo; it must end with `Result: VALID` for the package and `Result: INVALID` for the changed copy:

```shell
ledger/demo-mcp/demo.sh
```

<p style="text-align: justify;">

The script registers an agent, asks `alice` for a grant and approves it with her Keycloak sign-in, calls two allowed
tools and one denied tool through the gateway, lists the evidence, exports a package, verifies it offline and shows
that a changed entry fails verification.

</p>

## Agents

<p style="text-align: justify;">

These steps use the stack started above. Keep one terminal open for them, since step 1 sets a variable the later steps
use.

</p>

### Register Agents

Step 1. Keep the operator key in a variable and register two agents:

```shell
OP="Authorization: Bearer nexusphere-ledger-development-key-change-me"
curl -s -X POST http://localhost:8090/api/v1/agents -H "$OP" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","name":"Invoice agent","ownerId":"acme"}'
curl -s -X POST http://localhost:8090/api/v1/agents -H "$OP" -H "Content-Type: application/json" -d '{"agentId":"support-agent","name":"Support agent","ownerId":"acme"}'
```

<p style="text-align: justify;">

Each answer carries the agent's `apiKey` once. Keep it; a lost key is replaced with
`POST /api/v1/agents/{agentId}/key`. The Agents page of the web UI does the same.

</p>

### Grant Permissions

Step 2. Create a grant for each agent:

```shell
curl -s -X POST http://localhost:8090/api/v1/grants -H "$OP" -H "Content-Type: application/json" -d '{"principalId":"alice","agentId":"invoice-agent","actions":["tools/call"],"targets":["demo/read_file","demo/send_email"],"expiresAt":"2026-12-31T00:00:00Z","maxUses":20}'
curl -s -X POST http://localhost:8090/api/v1/grants -H "$OP" -H "Content-Type: application/json" -d '{"principalId":"alice","agentId":"support-agent","actions":["tools/call"],"targets":["demo/read_file"],"expiresAt":"2026-12-31T00:00:00Z"}'
```

<p style="text-align: justify;">

The compose ledger asks principals for consent, so both grants start `PENDING`. Sign in to the web UI as `alice` and
approve them; both must then show `ACTIVE`. Targets are `{server}/{tool}`; a trailing `*` matches a prefix, such as `demo/*`.

</p>

### Call Tools

Step 3. Call one granted and one not granted tool with the `apiKey` of `invoice-agent`:

```shell
curl -s http://localhost:8090/mcp/demo -H "Authorization: Bearer {apiKey}" -H "X-Ledger-Principal: alice" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/invoices/2026-10.txt"}}}'
curl -s http://localhost:8090/mcp/demo -H "Authorization: Bearer {apiKey}" -H "X-Ledger-Principal: alice" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"delete_file","arguments":{"path":"/invoices/2026-10.txt"}}}'
```

<p style="text-align: justify;">

The first call reaches the demo server and returns its answer. The second is denied with JSON-RPC error `-32003` and
never reaches the server. `X-Ledger-Principal` names the principal the agent acts for; the call is allowed only when
that principal gave the agent a matching grant.

</p>

### See the Evidence

Step 4. List the evidence of `invoice-agent`; the denied call is there too:

```shell
curl -s "http://localhost:8090/api/v1/evidence?agentId=invoice-agent" -H "$OP" | jq '.items[] | {sequence, action, target, decision, outcome}'
```

Step 5. Export a package and verify it offline; the verifier must print `Result: VALID`:

```shell
curl -s -X POST http://localhost:8090/api/v1/packages -H "$OP" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent"}' -o package.json
java -jar ledger/verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar --public-key "$(curl -s http://localhost:8090/api/v1/keys -H "$OP" | jq -r '.[] | select(.status == "ACTIVE") | .publicKey')" package.json
```

## Real Agents and MCP Servers

### Claude Code

```shell
claude mcp add --transport http ledger-demo http://localhost:8090/mcp/demo --header "Authorization: Bearer {apiKey}" --header "X-Ledger-Principal: alice"
```

<p style="text-align: justify;">

Claude Code then lists the demo tools and every call it makes goes through the ledger: granted tools run, others are
denied, and both are evidence. Any MCP client that speaks Streamable HTTP and can send headers works the same way,
such as Claude Desktop or an agent framework.

</p>

### Your Own MCP Server

<p style="text-align: justify;">

Each upstream MCP server is configured on the ledger by name and served at `/mcp/{name}`. The server must speak
Streamable HTTP; a stdio server needs an HTTP bridge in front of it. A key the upstream needs is sent by the ledger,
so the agent never sees it.

</p>

```yaml
LEDGER_MCP_SERVERS_FILES_URL: http://files-mcp:3000/mcp
LEDGER_MCP_SERVERS_FILES_AUTHORIZATION: Bearer upstream-secret
```

### Another Organization

<p style="text-align: justify;">

When an agent works with an agent of another organization, both sides run a ledger. The sending ledger decides the
call and attaches a mandate, the receiving ledger checks it and answers with a signed receipt, and both keep matching
evidence. The A2A gateway and its settings are in [Nexusphere Ledger](../ledger/README.md#a2a-gateway).

</p>

## Beside an Existing Gateway

<p style="text-align: justify;">

When agents already run behind a platform or gateway that decides their tool calls, such as Amazon Bedrock AgentCore,
Google Agent Gateway or agentgateway, keep it and let the ledger hold the proof. Register one ledger agent per real
agent, then have the gateway, a hook of the platform or the agent itself send every decision and its outcome as
evidence with that agent's key. The entries get the same chain, log, witnesses, statements and packages as calls that
pass through the ledger's own gateway.

</p>

```shell
curl -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","principalId":"alice","action":"tools/call","target":"send_email","decision":"ALLOW","reason":"agentcore-policy","outcome":"SUCCEEDED","correlationId":"session-42","attributes":{"gateway":"agentcore"}}'
```

<p style="text-align: justify;">

When the platform already sends OpenTelemetry traces, nothing has to call the API: point its OTLP exporter at the
Collector of the [OpenTelemetry Exporter](../ledger/otel-exporter/README.md), or add the `nexusphere` exporter to an
existing Collector. Every `execute_tool`, `invoke_agent` and MCP `tools/call` span becomes an evidence entry, with
the trace id as its correlation id, and other spans are ignored.

</p>

## Present It

| Step | Show                                                                  | Point                                               |
|------|-----------------------------------------------------------------------|-----------------------------------------------------|
| 1    | The problem in one sentence                                           | Agents act, but nobody can prove what they did      |
| 2    | `demo.sh`: two tools allowed, `delete_file` denied                    | The decision happens before the tool runs           |
| 3    | The web UI as `alice`: approve, then revoke a grant                   | The person, not the vendor, decides                 |
| 4    | Evidence and a package verified offline, then one changed entry fails | Nobody can change the record, not even the operator |
| 5    | Claude Code calling tools through the ledger                          | Works with real agents without changing them        |
| 6    | Two ledgers and a mandate, if the audience works across organizations | Trust between companies, not only inside one        |

<p style="text-align: justify;">

Start the stack before the talk and run `demo.sh` once, so images and Keycloak are warm. Keep the web UI open in a
second window signed in as `alice`.

</p>

## Stop

Step 1. Stop the services and remove the data:

```shell
docker compose --file ledger/compose.yaml --project-name ledger down
docker volume prune -f
```

## Next Steps

* Production secrets, backups and monitoring: [Operations](operations.md)
* Every setting and endpoint: [Nexusphere Ledger](../ledger/README.md)
* The web UI: [Nexusphere Frontend](../frontend/README.md)

##

**<p align="center">[Top](#ledger-guide)</p>**
