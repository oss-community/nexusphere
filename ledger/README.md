# <p align="center">Nexusphere Ledger</p>

<p align="center">A self-hosted, tamper-evident evidence ledger for the actions of AI agents.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Roadmap](#roadmap)
* [Modules](#modules)
* [Getting Started](#getting-started)
* [Environment Variables](#environment-variables)
* [Evidence Format](#evidence-format)
* [Verification](#verification)
* [Agents, Grants and Decisions](#agents-grants-and-decisions)
* [MCP Gateway](#mcp-gateway)
* [API](#api)
* [End-to-End Tests](ledger-e2e-tests/README.md)

## Purpose

<p style="text-align: justify;">

Every action an agent takes for a person or an organization is recorded as an evidence entry: which agent acted, for
which principal, what it did, on what target, whether it was allowed or denied and why, and how it ended. Entries form
a SHA-256 hash chain, the database rejects any change or deletion, and the ledger signs its head with Ed25519 at a fixed
interval. Anyone holding the published public key can check the chain and the checkpoints without trusting the server.

</p>

## Roadmap

| Phase | Name                       | Delivers                                                                       | Status |
|-------|----------------------------|--------------------------------------------------------------------------------|--------|
| L1    | Evidence core              | Hash chain, append-only storage, signed checkpoints, verification API          | ✓      |
| L2    | Grants and decisions       | Grants from a principal to an agent, ALLOW/DENY decisions recorded as evidence | ✓      |
| L3    | MCP gateway                | A gateway in front of MCP servers that decides and records every tool call     | ✓      |
| L4    | Evidence package           | Exported evidence package and an offline verifier CLI                          |        |
| L5    | Release                    | Compose file, demo and release                                                 |        |
| M1    | Mandate format             | Signed cross-organization mandates with a status list                          |        |
| M2    | Verifier SDK               | Mandate verification library                                                   |        |
| M3    | A2A and two-sided evidence | Mandates over A2A with evidence on both sides                                  |        |

## Modules

| Module                    | Responsibility                                                                                               |
|---------------------------|--------------------------------------------------------------------------------------------------------------|
| `ledger/chain`            | Canonical JSON, evidence entry, hash chain verifier, Ed25519 keys and checkpoints. No framework dependencies |
| `ledger/server`           | Spring Boot service: evidence API, PostgreSQL storage, checkpoint scheduler, verification                    |
| `ledger/ledger-e2e-tests` | End-to-end tests against the server and PostgreSQL                                                           |

## Getting Started

```shell
docker compose --file compose.yaml --project-name dev up -d postgresql
mvn install -DskipTests=true
LEDGER_PROFILES=postgresql,dev LEDGER_DATABASE_DB=nexusphere LEDGER_DATABASE_USERNAME=nexusphere LEDGER_DATABASE_PASSWORD=nexusphere mvn -pl ledger/server spring-boot:start
```

```shell
curl -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","principalId":"alice","action":"tools/call","target":"send_email","decision":"ALLOW","outcome":"SUCCEEDED","attributes":{"server":"mail"}}'
curl -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer nexusphere-ledger-development-key-change-me"
curl -X GET http://localhost:8090/api/v1/verification -H "Authorization: Bearer nexusphere-ledger-development-key-change-me"
```

```shell
mvn -pl ledger/server spring-boot:stop
```

```shell
mvn -pl ledger/ledger-e2e-tests -am verify
```

## Environment Variables

```yaml
LEDGER_HOST: 0.0.0.0
LEDGER_PORT: 8090
LEDGER_PROFILES: postgresql
LEDGER_DATABASE_HOST: localhost
LEDGER_DATABASE_PORT: 5432
LEDGER_DATABASE_DB: ledger
LEDGER_DATABASE_USERNAME: ledger
LEDGER_DATABASE_PASSWORD: ledger
LEDGER_API_KEY:
LEDGER_SIGNING_PRIVATE_KEY:
LEDGER_SIGNING_PUBLIC_KEY:
LEDGER_CHECKPOINT_INTERVAL: 1m
LEDGER_MCP_TIMEOUT: 60s
LEDGER_MCP_SERVERS_{NAME}_URL:
LEDGER_MCP_SERVERS_{NAME}_AUTHORIZATION:
```

<p style="text-align: justify;">

`LEDGER_API_KEY` and the signing keys have no default outside the `dev` profile, and the ledger refuses to start with
the published development secrets unless `dev` is active. The private key is a base64 PKCS#8 Ed25519 key and the public
key is its base64 X.509 encoding; the ledger refuses to start when they do not match. The tables live in the `ledger`
schema, so the ledger can share a database with the core.

</p>

```shell
openssl genpkey -algorithm ed25519 -outform DER -out ledger-signing.der
export LEDGER_SIGNING_PRIVATE_KEY=$(base64 -w0 ledger-signing.der)
export LEDGER_SIGNING_PUBLIC_KEY=$(openssl pkey -inform DER -in ledger-signing.der -pubout -outform DER | base64 -w0)
```

## Evidence Format

| Field           | Description                                                                                                   |
|-----------------|---------------------------------------------------------------------------------------------------------------|
| `format`        | `nexusphere-ledger/evidence/v1`                                                                               |
| `id`            | UUID assigned by the ledger                                                                                   |
| `sequence`      | Position in the chain, starting at 1 without gaps                                                             |
| `occurredAt`    | When the action happened, at most five minutes in the future                                                  |
| `recordedAt`    | When the ledger recorded it                                                                                   |
| `agentId`       | The acting agent (required)                                                                                   |
| `principalId`   | The person or organization the agent acted for (required)                                                     |
| `action`        | What was done, for example `tools/call` (required)                                                            |
| `target`        | The tool, resource or counterparty                                                                            |
| `decision`      | `ALLOW` or `DENY`                                                                                             |
| `reason`        | Why the decision was taken                                                                                    |
| `delegationId`  | The grant or mandate the agent acted under                                                                    |
| `inputHash`     | Lowercase SHA-256 of the input                                                                                |
| `outputHash`    | Lowercase SHA-256 of the output                                                                               |
| `outcome`       | `SUCCEEDED`, `FAILED`, `DENIED` or `PENDING` (required); `DENY` requires `DENIED`, `PENDING` requires `ALLOW` |
| `correlationId` | Caller correlation, for example a conversation                                                                |
| `attributes`    | Up to 32 string attributes                                                                                    |
| `previousHash`  | Hash of the previous entry; 64 zeros for the first entry                                                      |
| `hash`          | SHA-256 of the canonical JSON of every field except `hash`                                                    |

<p style="text-align: justify;">

Canonical JSON has sorted keys, no whitespace and `null` for absent fields. Timestamps are UTC with microseconds, for
example `2026-10-01T08:00:00.123456Z`. A checkpoint signs the canonical JSON of `format`
(`nexusphere-ledger/checkpoint/v1`), `sequence`, `headHash`, `createdAt` and `keyId`. The key ID is the first 16 hex
characters of the SHA-256 of the X.509 public key.

</p>

## Verification

<p style="text-align: justify;">

`GET /api/v1/verification` recomputes every hash and link, checks the signature of every checkpoint against the entry
it names and checks the head. The same checks run outside the server with `ledger/chain`: page through
`GET /api/v1/evidence`, feed the entries to `ChainVerifier`, and verify checkpoints with `SignedCheckpoint.verify` and the
key from `GET /api/v1/keys`.

</p>

## Agents, Grants and Decisions

<p style="text-align: justify;">

The operator holds `LEDGER_API_KEY`. Every agent is registered by the operator and gets its own API key, shown only
once and stored as a SHA-256 hash. An agent can record and read only its own evidence, grants and decisions; registering
agents, creating and revoking grants, creating checkpoints and running the full verification belong to the operator.

</p>

<p style="text-align: justify;">

A grant lets one agent act for one principal: a list of actions and a list of targets, each exact or ending with `*`,
valid until `expiresAt`, optionally from `notBefore` and for at most `maxUses` allowed decisions. Its terms are hashed as
the canonical JSON of `nexusphere-ledger/grant/v1` (`GrantTerms` in `ledger/chain`), and the hash is the `inputHash` of
the `grant/create` evidence, so anyone can check that a grant was not changed after it was recorded.

</p>

<p style="text-align: justify;">

Before acting, an agent asks for a decision. The ledger picks the active grant that covers the action and target and
expires first, counts one use, and records the decision as evidence with outcome `PENDING`, or `DENIED` with the reason.
The agent then reports `SUCCEEDED` or `FAILED` once; this is a second evidence entry carrying the `decisionId` attribute,
the grant as `delegationId` and the same `inputHash` and `correlationId`. Concurrent decisions never use a grant more
than `maxUses` times.

</p>

| Reason code        | Decision | Meaning                                                    |
|--------------------|----------|------------------------------------------------------------|
| `ALLOWED_BY_GRANT` | ALLOW    | An active grant covers the action and the target           |
| `AGENT_NOT_ACTIVE` | DENY     | The agent is unknown or disabled                           |
| `NO_ACTIVE_GRANT`  | DENY     | The principal has no active grant for this agent           |
| `NOT_COVERED`      | DENY     | Active grants exist, but none covers the action and target |
| `NOT_YET_VALID`    | DENY     | The covering grant starts later                            |
| `USES_EXHAUSTED`   | DENY     | The covering grant has used all its uses                   |

Agent, grant and decision changes are evidence too: `agent/register`, `agent/disable`, `agent/rotate-key`,
`grant/create` and `grant/revoke`, with the owner or the granting principal as `principalId`.

```shell
curl -X POST http://localhost:8090/api/v1/agents -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","name":"Invoice agent","ownerId":"acme"}'
curl -X POST http://localhost:8090/api/v1/grants -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"principalId":"alice","agentId":"invoice-agent","actions":["tools/call"],"targets":["send_email","read_*"],"expiresAt":"2026-12-31T00:00:00Z","maxUses":100}'
curl -X POST http://localhost:8090/api/v1/decisions -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d '{"principalId":"alice","action":"tools/call","target":"send_email"}'
curl -X POST http://localhost:8090/api/v1/decisions/{decisionId}/outcome -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d '{"outcome":"SUCCEEDED"}'
```

## MCP Gateway

<p style="text-align: justify;">

The ledger can stand in front of MCP servers that speak Streamable HTTP. Each server gets a name and a URL, for example
`LEDGER_MCP_SERVERS_FILES_URL=http://files-mcp:3000/mcp`, and an optional `Authorization` header the ledger sends to
it. The agent points its MCP client at `/mcp/{name}` with its own API key and names the principal it works for in the
`X-Ledger-Principal` header.

</p>

<p style="text-align: justify;">

Every `tools/call` is decided first, with action `tools/call` and target `{name}/{tool}`, so a grant on `files/*` or
`files/read_file` covers it. A denied call never reaches the server and is answered with JSON-RPC error `-32003` and
the decision ID and reason code in `error.data`. An allowed call is forwarded, and its outcome is reported from the
answer: a JSON-RPC error, `isError: true`, a non-2xx status or an unreachable server (`-32002`, HTTP 502) count as
`FAILED`. The input hash is the SHA-256 of the request body, the output hash is the SHA-256 of the answer, the MCP
session ID is the correlation ID, and every answer to a decided call carries the `X-Ledger-Decision` header. Other
messages, such as `initialize`, `tools/list` and notifications, pass through unchanged with the `Mcp-Session-Id`
header. Answers streamed as server-sent events are returned as one JSON response; batches and the server-to-client
stream (`GET`) are not supported.

</p>

```shell
curl -X POST http://localhost:8090/mcp/files -H "Authorization: Bearer {agentApiKey}" -H "X-Ledger-Principal: alice" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/tmp/report.txt"}}}'
```

## API

All `/api/**` endpoints require `Authorization: Bearer {LEDGER_API_KEY}` or an agent API key. Operator-only endpoints
answer `403 FORBIDDEN` to agents.

| Method | Path                               | Description                                                                       |
|--------|------------------------------------|-----------------------------------------------------------------------------------|
| POST   | `/api/v1/evidence`                 | Record an evidence entry; returns `201` with `Location`                           |
| GET    | `/api/v1/evidence/{id}`            | Get an evidence entry                                                             |
| GET    | `/api/v1/evidence`                 | List entries by `agentId`, `principalId`, `after` and `limit` (max 500)           |
| GET    | `/api/v1/ledger/head`              | Current sequence and hash                                                         |
| POST   | `/api/v1/checkpoints`              | Sign the current head now; `409 LEDGER_EMPTY` when nothing is recorded (operator) |
| GET    | `/api/v1/checkpoints/latest`       | Latest checkpoint                                                                 |
| GET    | `/api/v1/checkpoints`              | List checkpoints by `after` and `limit`                                           |
| GET    | `/api/v1/keys`                     | Public signing keys                                                               |
| GET    | `/api/v1/verification`             | Full verification report (operator)                                               |
| POST   | `/api/v1/agents`                   | Register an agent and return its API key once (operator)                          |
| GET    | `/api/v1/agents`                   | List agents by `after` and `limit` (operator)                                     |
| GET    | `/api/v1/agents/{agentId}`         | Get an agent                                                                      |
| POST   | `/api/v1/agents/{agentId}/disable` | Disable an agent and its key (operator)                                           |
| POST   | `/api/v1/agents/{agentId}/key`     | Issue a new API key; the old one stops working (operator)                         |
| POST   | `/api/v1/grants`                   | Create a grant (operator)                                                         |
| GET    | `/api/v1/grants`                   | List grants by `agentId`, `principalId`, `after` and `limit`                      |
| GET    | `/api/v1/grants/{id}`              | Get a grant with its status and uses                                              |
| POST   | `/api/v1/grants/{id}/revoke`       | Revoke a grant with an optional `reason` (operator)                               |
| POST   | `/api/v1/decisions`                | Decide an action for an agent and record it; returns `201`                        |
| GET    | `/api/v1/decisions/{id}`           | Get a decision and its outcome                                                    |
| POST   | `/api/v1/decisions/{id}/outcome`   | Report `SUCCEEDED` or `FAILED` once for an allowed decision                       |
| POST   | `/mcp/{server}`                    | MCP gateway: decide, forward and record a `tools/call`; forward other messages    |
| DELETE | `/mcp/{server}`                    | Close an MCP session on the server                                                |
| GET    | `/actuator/health`                 | Health, open for probes                                                           |

```json
{
  "code": "INVALID_REQUEST",
  "message": "The evidence has invalid fields.",
  "details": {
    "fields": {
      "principalId": "must not be blank"
    }
  }
}
```

##

**<p align="center">[Top](#nexusphere-ledger)</p>**
