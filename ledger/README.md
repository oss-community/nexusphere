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
| L2    | Grants and decisions       | Grants from a principal to an agent, ALLOW/DENY decisions recorded as evidence |        |
| L3    | MCP gateway                | A gateway in front of MCP servers that decides and records every tool call     |        |
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

| Field           | Description                                                              |
|-----------------|--------------------------------------------------------------------------|
| `format`        | `nexusphere-ledger/evidence/v1`                                          |
| `id`            | UUID assigned by the ledger                                              |
| `sequence`      | Position in the chain, starting at 1 without gaps                        |
| `occurredAt`    | When the action happened, at most five minutes in the future            |
| `recordedAt`    | When the ledger recorded it                                              |
| `agentId`       | The acting agent (required)                                              |
| `principalId`   | The person or organization the agent acted for (required)               |
| `action`        | What was done, for example `tools/call` (required)                      |
| `target`        | The tool, resource or counterparty                                       |
| `decision`      | `ALLOW` or `DENY`                                                        |
| `reason`        | Why the decision was taken                                               |
| `delegationId`  | The grant or mandate the agent acted under                               |
| `inputHash`     | Lowercase SHA-256 of the input                                           |
| `outputHash`    | Lowercase SHA-256 of the output                                          |
| `outcome`       | `SUCCEEDED`, `FAILED` or `DENIED` (required); `DENY` requires `DENIED`   |
| `correlationId` | Caller correlation, for example a conversation                           |
| `attributes`    | Up to 32 string attributes                                               |
| `previousHash`  | Hash of the previous entry; 64 zeros for the first entry                 |
| `hash`          | SHA-256 of the canonical JSON of every field except `hash`               |

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

## API

All `/api/**` endpoints require `Authorization: Bearer {LEDGER_API_KEY}`.

| Method | Path                         | Description                                                              |
|--------|------------------------------|--------------------------------------------------------------------------|
| POST   | `/api/v1/evidence`           | Record an evidence entry; returns `201` with `Location`                  |
| GET    | `/api/v1/evidence/{id}`      | Get an evidence entry                                                    |
| GET    | `/api/v1/evidence`           | List entries by `agentId`, `principalId`, `after` and `limit` (max 500)  |
| GET    | `/api/v1/ledger/head`        | Current sequence and hash                                                |
| POST   | `/api/v1/checkpoints`        | Sign the current head now; `409 LEDGER_EMPTY` when nothing is recorded   |
| GET    | `/api/v1/checkpoints/latest` | Latest checkpoint                                                        |
| GET    | `/api/v1/checkpoints`        | List checkpoints by `after` and `limit`                                  |
| GET    | `/api/v1/keys`               | Public signing keys                                                      |
| GET    | `/api/v1/verification`       | Full verification report                                                 |
| GET    | `/actuator/health`           | Health, open for probes                                                  |

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
