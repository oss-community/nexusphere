# <p align="center">Nexusphere Ledger</p>

<p align="center">A self-hosted, tamper-evident evidence ledger for the actions of AI agents.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Modules](#modules)
* [Getting Started](#getting-started)
* [Environment Variables](#environment-variables)
* [Evidence Format](#evidence-format)
* [Verification](#verification)
* [Transparency Log and Witnesses](#transparency-log-and-witnesses)
* [SCITT Statements and Receipts](#scitt-statements-and-receipts)
* [Evidence Packages](#evidence-packages)
* [Index Proofs](#index-proofs)
* [Key Rotation](#key-rotation)
* [Key Revocation](#key-revocation)
* [Agents, Grants and Decisions](#agents-grants-and-decisions)
* [Policies](#policies)
* [Principal Login and Consent](#principal-login-and-consent)
* [MCP Gateway](#mcp-gateway)
* [Mandates](#mandates)
* [Agent Keys and Key Binding](#agent-keys-and-key-binding)
* [Agent Track Records](#agent-track-records)
* [A2A Gateway](#a2a-gateway)
* [HTTP Message Signatures](#http-message-signatures)
* [Model and Prompt](#model-and-prompt)
* [Compliance Profiles](#compliance-profiles)
* [Erasure](#erasure)
* [Retention and Legal Holds](#retention-and-legal-holds)
* [Checkpoint Timestamps](#checkpoint-timestamps)
* [Rekor Anchoring](#rekor-anchoring)
* [Reports and Dispute Packages](#reports-and-dispute-packages)
* [Conformance Vectors](#conformance-vectors)
* [API](#api)
* [End-to-End Tests](ledger-e2e-tests/README.md)
* [Ledger Guide](../docs/ledger-guide.md)
* [Operations](../docs/operations.md)

## Purpose

<p style="text-align: justify;">

Every action an agent takes for a person or an organization is recorded as an evidence entry: which agent acted, for
which principal, what it did, on what target, whether it was allowed or denied and why, and how it ended. Entries form
a SHA-256 hash chain, the database rejects any change or deletion, and the ledger signs its head with Ed25519 at a fixed
interval. Anyone holding the published public key can check the chain and the checkpoints without trusting the server.

</p>

<p style="text-align: justify;">

The ledger is an evidence layer, not another agent platform. Agent identity and per-call decisions are already offered
by Amazon Bedrock AgentCore, Google Agent Gateway, Microsoft Entra Agent ID and open gateways such as agentgateway. The
ledger keeps them and records what they decide: a gateway, a platform hook or the agent itself sends each decision and
outcome to `POST /api/v1/evidence` (or `POST /api/v1/evidence/batch`) with its own agent key. Where nothing decides
yet, the ledger's MCP and A2A gateways do it. What the ledger adds is the proof: a transparency log that witnesses
cosign, SCITT statements and receipts, evidence packages with selective disclosure, mandates other organizations can
check, and receipts both sides of an exchange hold.

</p>

## Modules

| Module                                                  | Responsibility                                                                                              |
|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| [`ledger/chain`](chain/README.md)                       | Evidence format, hash chain, Merkle log, signed notes, COSE statements, receipts. No framework dependencies |
| [`ledger/mandate`](mandate/README.md)                   | SD-JWT VC mandates, status lists, JWK keys and the mandate verifier SDK for other organizations             |
| [`ledger/server`](server/README.md)                     | Spring Boot service: evidence API, PostgreSQL storage, checkpoint scheduler, verification                   |
| [`ledger/verifier`](verifier/README.md)                 | Command-line verifier for evidence packages and mandates, with no server dependency                         |
| [`ledger/demo-mcp`](demo-mcp/README.md)                 | Demo MCP server with file and mail tools, and the demo script                                               |
| [`ledger/ledger-e2e-tests`](ledger-e2e-tests/README.md) | End-to-end tests against the server and PostgreSQL                                                          |
| [`ledger/sdk/python`](sdk/python/README.md)             | Python client and offline verifier for packages, statements, receipts and mandates                          |
| [`ledger/sdk/typescript`](sdk/typescript/README.md)     | TypeScript client and offline verifier for Node and browsers, with no runtime dependencies                  |
| [`ledger/otel-exporter`](otel-exporter/README.md)       | OpenTelemetry Collector exporter that turns agent tool call spans into evidence, and a ready Collector      |
| [`ledger/adapters`](adapters/README.md)                 | Adapters for LangGraph, the OpenAI Agents SDK, Amazon Bedrock AgentCore and agentgateway                    |
| [`ledger/verifier-web`](verifier-web/README.md)         | Package verifier as one HTML file that runs in the browser with no network                                  |

## Getting Started

<p style="text-align: justify;">

Every command runs in the root of the repository. Each path below is a list of steps; do them in order and check the
result of each step before the next one.

</p>

### Prerequisites

| Tool   | Version | Check                    |
|--------|---------|--------------------------|
| Java   | 21      | `java -version`          |
| Maven  | 3.9     | `mvn -version`           |
| Docker | any     | `docker compose version` |
| jq     | any     | `jq --version`           |

### Dockerized

Step 1. Build the server, the verifier and the demo MCP server:

```shell
mvn -pl ledger/server,ledger/verifier,ledger/demo-mcp -am package -DskipTests=true
```

Step 2. Start every service:

```shell
docker compose --file ledger/compose.yaml --project-name ledger up -d --build
```

Step 3. Check that the ledger is up; the answer must be `{"status":"UP"}`:

```shell
curl -X GET http://localhost:8090/actuator/health
```

| Service             | URL                       | Description                                      |
|---------------------|---------------------------|--------------------------------------------------|
| `ledger`            | http://localhost:8090     | The ledger with the `dev` profile                |
| `demo-mcp`          | http://localhost:8091/mcp | Demo MCP server, reached through `/mcp/demo`     |
| `ledger-postgresql` | localhost:5433            | PostgreSQL, user, password and database `ledger` |
| `ledger-adminer`    | http://localhost:8092     | Adminer for `ledger-postgresql`                  |
| `keycloak`          | http://localhost:8180     | Keycloak, admin `admin`/`admin`                  |
| `ledger-ui`         | http://localhost:5173     | The web UI, built from `frontend`                |
| `vault`             | http://localhost:8200     | Vault in development mode with a transit key     |
| `otel-collector`    | localhost:4317, 4318      | OpenTelemetry Collector, OTLP gRPC and HTTP      |
| `prometheus`        | http://localhost:9090     | Prometheus with the ledger alerts                |
| `alertmanager`      | http://localhost:9093     | Alertmanager, no channel until one is set        |

<p style="text-align: justify;">

Keycloak imports the `nexusphere` realm from `ledger/keycloak/nexusphere-realm.json` with the public client
`nexusphere-ledger` and the principals `alice` and `bob`, whose passwords are their names. The ledger in the compose
file trusts this realm, so grants created by the operator wait for the principal's approval.

</p>

<p style="text-align: justify;">

The web UI on http://localhost:5173 opens with the operator key or a Keycloak sign-in; it is described in
[Nexusphere Frontend](../frontend/README.md). The MCP addresses have no web page. `/mcp/demo` on the ledger and `/mcp`
on `demo-mcp` accept only MCP JSON-RPC `POST` requests, and the gateway also needs an agent API key, so opening them in
a browser shows nothing. Run the demo below to see calls go through the gateway.

</p>

Step 4. Run the demo; it must end with `Result: VALID` for the package and `Result: INVALID` for the changed copy:

```shell
ledger/demo-mcp/demo.sh
```

<p style="text-align: justify;">

The script registers an agent, asks `alice` to grant it `read_file` and `send_email` on the demo server, signs `alice`
in with Keycloak to approve the grant when the ledger asks for consent, calls both through the gateway, has
`delete_file` denied, lists the recorded evidence, exports an evidence package, verifies it offline with the ledger's
published key and shows that a changed entry fails verification. `LEDGER_URL`, `LEDGER_API_KEY`, `KEYCLOAK_URL`,
`VERIFIER_JAR` and `WORK_DIR` override its defaults.

</p>

Step 5. Stop everything and remove the data:

```shell
docker compose --file ledger/compose.yaml --project-name ledger down
docker volume prune -f
```

### Kubernetes

<p style="text-align: justify;">

`ledger/kube-dev.yaml` runs the same services as the compose file in the `dev` namespace. The Keycloak realm comes from
the `keycloak-realm` ConfigMap. Keycloak and the UI are reached on the same local ports as with compose, so the
sign-in redirect and the token issuer stay `http://localhost:8180` and `http://localhost:5173`.

</p>

Step 1. Build the jars and the images:

```shell
mvn -pl ledger/server,ledger/verifier,ledger/demo-mcp -am package -DskipTests=true
docker compose --file ledger/compose.yaml --project-name ledger build
```

Step 2. Apply the manifest:

```shell
kubectl apply -f ledger/kube-dev.yaml
```

Step 3. Wait until every pod is `Running`:

```shell
kubectl get all -n dev
```

Step 4. Forward the ports, each in its own terminal:

```shell
kubectl port-forward service/ledger 8090:8090 -n dev
kubectl port-forward service/keycloak 8180:8080 -n dev
kubectl port-forward service/ledger-ui 5173:80 -n dev
kubectl port-forward service/ledger-adminer 8092:8080 -n dev
kubectl port-forward service/otel-collector 4318:4318 -n dev
```

Step 5. Check the ledger and run the demo as in [Dockerized](#dockerized):

```shell
curl -X GET http://localhost:8090/actuator/health
ledger/demo-mcp/demo.sh
```

Step 6. Remove everything:

```shell
kubectl delete -f ledger/kube-dev.yaml
```

### Run

<p style="text-align: justify;">

This path runs the server from Maven against the PostgreSQL of the compose file, for development.

</p>

Step 1. Start PostgreSQL only:

```shell
docker compose --file ledger/compose.yaml --project-name ledger up -d ledger-postgresql
```

Step 2. Build and start the server:

```shell
mvn install -DskipTests=true
LEDGER_PROFILES=postgresql,dev LEDGER_DATABASE_PORT=5433 mvn -pl ledger/server spring-boot:start
```

Step 3. Record an entry, sign a checkpoint and verify the chain; the last answer must hold `"valid": true`:

```shell
curl -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","principalId":"alice","action":"tools/call","target":"send_email","decision":"ALLOW","outcome":"SUCCEEDED","attributes":{"server":"mail"}}'
curl -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer nexusphere-ledger-development-key-change-me"
curl -X GET http://localhost:8090/api/v1/verification -H "Authorization: Bearer nexusphere-ledger-development-key-change-me"
```

Step 4. Stop the server and PostgreSQL:

```shell
mvn -pl ledger/server spring-boot:stop
docker compose --file ledger/compose.yaml --project-name ledger down
```

### Verify

Step 1. Run the unit, integration and end-to-end tests; Docker must be running for Testcontainers:

```shell
mvn -pl ledger/ledger-e2e-tests -am verify
```

### Release

<p style="text-align: justify;">

Ledger releases are tagged `ledger-v{version}` and described in the [Changelog](CHANGELOG.md). The release artifacts
are the server jar (`ledger/server/target/server-*-exec.jar`), the verifier jar
(`ledger/verifier/target/verifier-*-exec.jar`) and the two Docker images built by the compose file.

</p>

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
LEDGER_SIGNING_PROVIDER: local
LEDGER_SIGNING_PRIVATE_KEY:
LEDGER_SIGNING_PUBLIC_KEY:
LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY:
LEDGER_SIGNING_UNENDORSED_ROTATION: false
LEDGER_SIGNING_VAULT_ADDRESS:
LEDGER_SIGNING_VAULT_TOKEN:
LEDGER_SIGNING_VAULT_TOKEN_FILE:
LEDGER_SIGNING_VAULT_NAMESPACE:
LEDGER_SIGNING_VAULT_MOUNT: transit
LEDGER_SIGNING_VAULT_KEY: nexusphere-ledger
LEDGER_SIGNING_VAULT_TIMEOUT: 10s
LEDGER_CHECKPOINT_INTERVAL: 1m
LEDGER_COMPLIANCE_PROFILES: baseline
LEDGER_COMPLIANCE_PROFILE_DIRECTORY:
LEDGER_COMPLIANCE_REGION:
LEDGER_COMPLIANCE_RETENTION_INTERVAL: 1h
LEDGER_TIMESTAMP_URL:
LEDGER_TIMESTAMP_CERTIFICATES:
LEDGER_TIMESTAMP_TIMEOUT: 10s
LEDGER_REKOR_URL:
LEDGER_REKOR_KEY:
LEDGER_REKOR_TIMEOUT: 10s
LEDGER_LOG_ORIGIN:
LEDGER_LOG_WITNESS_TIMEOUT: 10s
LEDGER_LOG_WITNESSES_{NAME}_URL:
LEDGER_LOG_WITNESSES_{NAME}_KEY:
LEDGER_LOG_WATCHED_{NAME}_KEY:
LEDGER_MCP_TIMEOUT: 60s
LEDGER_MCP_SERVERS_{NAME}_URL:
LEDGER_MCP_SERVERS_{NAME}_AUTHORIZATION:
LEDGER_MANDATE_ISSUER: http://localhost:8090
LEDGER_MANDATE_STATUS_LIST_TTL: 5m
LEDGER_A2A_TIMEOUT: 60s
LEDGER_A2A_REQUEST_MAX_AGE: 5m
LEDGER_A2A_STATUS_LIST_CACHE: 30s
LEDGER_A2A_TRUSTED_ISSUERS:
LEDGER_A2A_PEERS_{NAME}_URL:
LEDGER_A2A_PEERS_{NAME}_ISSUER:
LEDGER_A2A_AGENTS_{NAME}_URL:
LEDGER_A2A_AGENTS_{NAME}_AUTHORIZATION:
LEDGER_STREAM_TIMEOUT: 1h
LEDGER_OIDC_ISSUER:
LEDGER_OIDC_AUDIENCE:
LEDGER_OIDC_JWKS_URI:
LEDGER_OIDC_PRINCIPAL_CLAIM: sub
LEDGER_OIDC_CLIENT_ID:
LEDGER_SECRETS_DIR: /run/secrets/
LEDGER_MANAGEMENT_PORT:
LEDGER_RATE_LIMIT_PER_MINUTE: 1200
LEDGER_RATE_LIMIT_BURST: 200
LEDGER_POLICY_DEFAULT_EFFECT: permit
LEDGER_APPROVAL_TIMEOUT: 15m
LEDGER_APPROVAL_POLL_INTERVAL: 5s
LEDGER_APPROVAL_WEBHOOK_URL:
LEDGER_APPROVAL_CIBA_ENDPOINT:
LEDGER_APPROVAL_CIBA_TOKEN_ENDPOINT:
LEDGER_APPROVAL_CIBA_CLIENT_ID:
LEDGER_APPROVAL_CIBA_CLIENT_SECRET:
LEDGER_APPROVAL_CIBA_SCOPE: openid
```

<p style="text-align: justify;">

`LEDGER_API_KEY` and the signing keys have no default outside the `dev` profile, and the ledger refuses to start with
the published development secrets unless `dev` is active. The private key is a base64 PKCS#8 Ed25519 key and the public
key is its base64 X.509 encoding; the ledger refuses to start when they do not match. With
`LEDGER_SIGNING_PROVIDER=vault` the private key stays in a HashiCorp Vault transit key and the `LEDGER_SIGNING_VAULT_*`
settings replace both keys, as in [Keys in Vault](#keys-in-vault). The tables live in the `ledger`
schema, so the ledger can share a database with other applications. Any setting can also come from a file of the same name in
`LEDGER_SECRETS_DIR`, which is how a secret manager hands over keys; backups, keys and metrics are described in
[Operations](../docs/operations.md). Each caller, by key or by address when it has none, gets
`LEDGER_RATE_LIMIT_PER_MINUTE` requests a minute with bursts up to `LEDGER_RATE_LIMIT_BURST`; over it the answer is 429
`RATE_LIMIT_EXCEEDED` with `Retry-After`, and `0` turns the limit off.

</p>

```shell
openssl genpkey -algorithm ed25519 -outform DER -out ledger-signing.der
export LEDGER_SIGNING_PRIVATE_KEY=$(base64 -w0 ledger-signing.der)
export LEDGER_SIGNING_PUBLIC_KEY=$(openssl pkey -inform DER -in ledger-signing.der -pubout -outform DER | base64 -w0)
```

## Evidence Format

| Field           | Description                                                                                                   |
|-----------------|---------------------------------------------------------------------------------------------------------------|
| `format`        | `nexusphere-ledger/evidence/v2`                                                                               |
| `id`            | UUID assigned by the ledger                                                                                   |
| `sequence`      | Position in the chain, starting at 1 without gaps                                                             |
| `occurredAt`    | When the action happened, at most five minutes in the future                                                  |
| `recordedAt`    | When the ledger recorded it                                                                                   |
| `agentId`       | The acting agent (required)                                                                                   |
| `principalId`   | The person or organization the agent acted for (required, personal)                                           |
| `action`        | What was done, for example `tools/call` (required)                                                            |
| `target`        | The tool, resource or counterparty (personal)                                                                 |
| `decision`      | `ALLOW` or `DENY`                                                                                             |
| `reason`        | Why the decision was taken (personal)                                                                         |
| `delegationId`  | The grant or mandate the agent acted under                                                                    |
| `inputHash`     | Lowercase SHA-256 of the input                                                                                |
| `outputHash`    | Lowercase SHA-256 of the output                                                                               |
| `outcome`       | `SUCCEEDED`, `FAILED`, `DENIED` or `PENDING` (required); `DENY` requires `DENIED`, `PENDING` requires `ALLOW` |
| `model`         | The model the agent ran: `name`, `version`, `promptHash` and `configHash`, each optional                      |
| `correlationId` | Caller correlation, for example a conversation (personal)                                                     |
| `attributes`    | Up to 32 string attributes (personal)                                                                         |
| `salts`         | A random 16-byte base64url salt for each personal field; `null` once erased                                   |
| `commitments`   | The commitment of each personal field                                                                         |
| `erased`        | `true` when the personal fields were erased                                                                   |
| `previousHash`  | Hash of the previous entry; 64 zeros for the first entry                                                      |
| `hash`          | SHA-256 of the canonical JSON of `format`, `sequence`, `previousHash` and `contentHash`                       |

<p style="text-align: justify;">

The personal fields are `principalId`, `target`, `reason`, `correlationId` and each attribute as
`attributes.{name}`. The hash never covers their values, only their commitments: the SHA-256 of the canonical JSON of
`field`, `salt` and `value`, where an absent value is `null`. The content hash is the SHA-256 of the canonical JSON of
`format`, `id`, `occurredAt`, `recordedAt`, `agentId`, `action`, `decision`, `delegationId`, `inputHash`,
`outputHash`, `outcome`, `model` when the entry names one, and `commitments`. Whoever holds an entry with its salts recomputes the commitments and
the hash; once the salts and values are [erased](#erasure), the stored commitments still prove the entry's place in the
chain. The link (`sequence`, `previousHash`, `contentHash`, `hash`) can be checked without the content, so a
package can keep entries private and still prove the chain. Canonical JSON has sorted keys, no whitespace and `null`
for absent fields. Timestamps are UTC with microseconds, for example `2026-10-01T08:00:00.123456Z`. A checkpoint signs
the canonical JSON of `format` (`nexusphere-ledger/checkpoint/v2`), `sequence`, `headHash`, `createdAt`, `keyId` and
`profiles`, the `id` and `digest` of each active [compliance profile](#compliance-profiles) sorted by `id`.
Checkpoints signed before the ledger had profiles keep `nexusphere-ledger/checkpoint/v1` without `profiles`, and still
verify. The key ID is the first 16 hex characters of the SHA-256 of the X.509 public key.

</p>

<p style="text-align: justify;">

The ledger keeps the personal values and salts of each entry encrypted with AES-256-GCM under a data key of its
principal, in `evidence_personal` and `principal_key`; `evidence_record` holds only the commitments and a random
reference to the principal. Format v2 replaced v1 before release 1.0.0, and a ledger database that still holds v1
evidence does not migrate: recreate it.

</p>

## Verification

<p style="text-align: justify;">

`GET /api/v1/verification` recomputes every hash and link, checks the signature of every checkpoint against the entry
it names and checks the head. The same checks run outside the server with `ledger/chain`: page through
`GET /api/v1/evidence`, feed the entries to `ChainVerifier`, and verify each checkpoint with `SignedCheckpoint.verify` and
the key it names from `GET /api/v1/keys`.

</p>

## Transparency Log and Witnesses

<p style="text-align: justify;">

Besides the hash chain, every entry is a leaf of an RFC 9162 Merkle tree: the leaf is the 32-byte entry hash, so leaf
`n - 1` is the entry with sequence `n`. Whenever the ledger signs a checkpoint it also signs a log checkpoint in the
C2SP signed-note format (`origin`, tree size and base64 root hash, then an Ed25519 signature line). The origin is
`LEDGER_LOG_ORIGIN`, or the mandate issuer without its scheme, for example `localhost:8090`. With the tree, anyone can
prove that one entry is in the log with about `log2(n)` hashes, and that a later checkpoint extends an earlier one
without rewriting it.

</p>

<p style="text-align: justify;">

A ledger alone can still show different histories to different people. Witnesses close that gap: each witness keeps
the last checkpoint it saw for a log and cosigns a new one only with a valid consistency proof from it. Witnesses follow
the C2SP `tlog-witness` protocol and sign `tlog-cosignature/v1` lines, so any compatible witness works, and every ledger
is one: set `LEDGER_LOG_WATCHED_{NAME}_KEY` to the verifier key of each log it should witness, with `{NAME}` equal to
the log's origin. To ask witnesses for cosignatures, set `LEDGER_LOG_WITNESSES_{NAME}_URL` to the witness's base URL
(the ledger appends `/add-checkpoint`) and `LEDGER_LOG_WITNESSES_{NAME}_KEY` to its verifier key. The ledger asks every
witness after each checkpoint and keeps each cosignature it gets; a witness that is down is asked again next time.

</p>

```shell
curl http://localhost:8090/public/v1/log/key
curl http://localhost:8090/public/v1/witness/key
curl http://localhost:8090/public/v1/log/checkpoint
curl "http://localhost:8090/api/v1/log/proofs/inclusion?sequence=7" -H "Authorization: Bearer nexusphere-ledger-development-key-change-me"
curl "http://localhost:8090/api/v1/log/proofs/consistency?firstSize=7&secondSize=42" -H "Authorization: Bearer nexusphere-ledger-development-key-change-me"
```

```text
localhost:8090
42
0dpbGc4h7JcvSy6dXq0XpLM4pN0iA0p9cX0XQ4nHqEo=

— localhost:8090 b4Ex7xF0kZ0G1Xo9ZfPu0eQyD3l8Q3mJ2tV9...
— partner.example b8Gk2wAAAABnBl0w0Tq9m3Q1...
```

<p style="text-align: justify;">

`GET /api/v1/verification` also rebuilds the tree from the chain and checks every log checkpoint against it, so a log
checkpoint that does not match the evidence is reported like a broken link.

</p>

## SCITT Statements and Receipts

<p style="text-align: justify;">

Every entry is also available as an IETF SCITT signed statement and a log receipt, both COSE_Sign1 (`application/cose`)
signed with EdDSA by the ledger key. The statement is a COSE hash envelope: its payload is the 32-byte content hash
(`payload_hash_alg` SHA-256, preimage content type `application/vnd.nexusphere.evidence+json`), its CWT claims name
the mandate issuer as `iss` and `urn:uuid:{id}` as `sub`, and the protected headers `nexusphere-sequence` and
`nexusphere-previous-hash` place it in the chain, so the Merkle leaf can be computed from the statement alone. The
receipt follows RFC 9942 with the RFC 9162 tree algorithm: the inclusion proof is in the unprotected header (396) and
the signed payload is the tree root, which is left out of the message. A receipt is issued for a log checkpoint, the
latest by default or the one named by `treeSize`, and the ledger signs a new checkpoint first when the entry is not in
the latest one yet.

</p>

<p style="text-align: justify;">

Only the content hash leaves the ledger in a statement, so a statement can be shared without the entry. Whoever has
the entry checks it against the statement's payload. The formats are standard COSE and any COSE library can read them,
but the evidence fields are Nexusphere's own; they are not an IETF agent-action profile. A2A receipts stay JWS and
name the evidence sequence and hash of the exchange, whose statement and receipt the receiving ledger serves like
any other.

</p>

```shell
curl http://localhost:8090/api/v1/evidence/{id}/statement -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -o statement.cose
curl http://localhost:8090/api/v1/evidence/{id}/receipt -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -o receipt.cose
java -jar ledger/verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar statement --public-key "$(curl -s http://localhost:8090/api/v1/keys -H 'Authorization: Bearer nexusphere-ledger-development-key-change-me' | jq -r '.[] | select(.status == "ACTIVE") | .publicKey')" --receipt receipt.cose statement.cose
```

```text
Nexusphere Ledger evidence statement
  Issuer      : http://localhost:8090
  Subject     : urn:uuid:7c0e5a52-8f0b-4d1e-9a51-3c1f0b8e2d44
  Sequence    : 7
  Content hash: 5e1c...
  Entry hash  : 9a40...
  Signature   : valid, key 3f2a9c0d41b7e865
  Receipt     : valid, log localhost:8090 at 42 entries, root 0dpbGc4h7JcvSy6dXq0XpLM4pN0iA0p9cX0XQ4nHqEo=
Result: VALID
```

## Evidence Packages

<p style="text-align: justify;">

`POST /api/v1/packages` exports an evidence package for an auditor, a court or a counterparty. The request selects
evidence by `agentId`, `principalId`, `fromSequence` and `toSequence`. The package (`nexusphere-ledger/package/v1`)
holds the public keys with their rotation records, an anchor checkpoint (the last checkpoint before the first selected
entry, or genesis), an end checkpoint (the first checkpoint at or after the last selected entry, created at the head
when none exists yet) and every link between them. Only selected links carry their full entry; all others carry only
their link, so nothing outside the selection is disclosed. A package holds at most 200,000 links. Its `log` field
carries the log checkpoint at the end checkpoint's sequence with every cosignature the ledger holds, and for each
disclosed entry an inclusion proof, its SCITT statement and its receipt for that checkpoint.

</p>

<p style="text-align: justify;">

The verifier needs no server and no database. It checks the signatures of both checkpoints, recomputes every link from
the anchor to the end checkpoint, checks every disclosed entry against its content hash and the scope of the package,
and exits with `0` for a valid package, `1` for an invalid one and `2` for a usage error. Pass the ledger's public key
from `GET /api/v1/keys` (or a key published elsewhere) with `--public-key` so the verifier does not trust the key inside
the package. When the package has a `log`, the verifier also checks that the log checkpoint is signed by the ledger,
covers the end checkpoint and proves every disclosed entry, and that each statement and receipt is signed by a trusted
key, describes its entry and proves it in the same log checkpoint. Pass each witness's verifier key with `--witness` and the
number of cosignatures needed with `--witnesses-required` (all given witnesses by default) to require that independent
witnesses saw the same log. Pass the ledger's current key list from `GET /api/v1/keys` with `--keys` so the verifier
also applies key revocations that happened after the package was exported, as in [Key Revocation](#key-revocation).

</p>

```shell
curl -X POST http://localhost:8090/api/v1/packages -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent"}' -o package.json
mvn -pl ledger/verifier -am package -DskipTests=true
java -jar ledger/verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar --public-key "$(curl -s http://localhost:8090/api/v1/keys -H 'Authorization: Bearer nexusphere-ledger-development-key-change-me' | jq -r '.[] | select(.status == "ACTIVE") | .publicKey')" package.json
```

```text
Nexusphere Ledger evidence package
  Signing key : 3f2a9c0d41b7e865 (pinned)
  Checkpoint  : sequence 42, signed at 2026-10-07T12:00:00.000123Z
  Anchor      : checkpoint 40
  Chain       : 2 links from sequence 41
  Disclosed   : 1 entries, agent invoice-agent
  Log         : localhost:8090, 42 entries, 1 disclosed entries proven
  Receipts    : 1 SCITT statements with receipts
  Witnesses   : partner.example
Result: VALID
```

## Index Proofs

<p style="text-align: justify;">

A package shows what happened; an index proof shows that something did not. The ledger keeps an index of the evidence
in a sparse Merkle tree keyed by four kinds of term: an `agent` by `agentId`, an `action` by `agentId` and `action`, an
`input` by `inputHash`, and a `grant` by `delegationId`, counting only the actions the grant allowed. Each leaf holds
how many entries name the term and the first and last sequence among them. For a checkpoint the ledger signs the root
of the index with its key in the format `nexusphere-ledger/index/v1`, over `sequence`, `headHash`, `root`,
`createdAt` and `keyId`, once, the first time someone asks, and keeps that signed root. `POST /api/v1/index/proofs`
takes a `term` and an optional checkpoint `sequence`, by default the latest, and answers a proof that the term occurs
with its count, or that no entry up to that checkpoint names it, with the path to the signed root and the ledger's
keys. `nexusphere-ledger-verify index` checks it offline, and with `--package` that it belongs to the checkpoint of a
package. The index uses no personal field, so a proof never discloses a principal, a target or a reason.

</p>

<p style="text-align: justify;">

The root is a statement signed by the ledger, not a proof computed by the verifier: a false absence would be a signed
statement the ledger cannot take back, which an auditor with the full evidence can expose by recomputing the index.

</p>

Step 1. Start the ledger as in [Run](#run), then record an action and sign a checkpoint:

```shell
curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"payments-agent","principalId":"alice","action":"tools/call","target":"bank/pay_invoice","inputHash":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","decision":"ALLOW","outcome":"SUCCEEDED"}' | jq -c '{sequence}'
curl -s -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{sequence}'
```

Step 2. Ask for a proof that the payment input was recorded; the answer must show a `count` of 1:

```shell
curl -s -X POST http://localhost:8090/api/v1/index/proofs -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"term":{"type":"input","inputHash":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}}' | jq -c .leaf
```

Step 3. Ask for a proof that the agent never deleted anything; the answer must show `null`:

```shell
curl -s -X POST http://localhost:8090/api/v1/index/proofs -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"term":{"type":"action","agentId":"payments-agent","action":"files/delete"}}' > proof.json
jq -c .leaf proof.json
```

Step 4. Verify the proof with the ledger's key pinned; the output must show
`Finding     : no entry up to sequence` and `Result: VALID`:

```shell
LEDGER_PUBLIC_KEY=$(curl -s http://localhost:8090/api/v1/keys -H "Authorization: Bearer $LEDGER_API_KEY" | jq -r '.[] | select(.status == "ACTIVE") | .publicKey')
java -jar verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar index --public-key "$LEDGER_PUBLIC_KEY" proof.json
```

## Key Rotation

<p style="text-align: justify;">

The ledger keeps every signing key it has used. Checkpoints, mandates, status lists and A2A receipts are signed with the
active key, and everything signed with a retired key still verifies, because each signature names its key and the
retired keys stay published in `GET /api/v1/keys`, `GET /public/v1/keys` and every evidence package. A revoked key
stays in `GET /api/v1/keys` and in packages, with its revocation, but leaves `GET /public/v1/keys`.

</p>

<p style="text-align: justify;">

To rotate, start the ledger with a new key pair in `LEDGER_SIGNING_PRIVATE_KEY` and `LEDGER_SIGNING_PUBLIC_KEY` and the
old private key in `LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY`. At startup the ledger retires the old key and stores a
rotation record (`nexusphere-ledger/key-rotation/v1`) that both keys sign: the old key endorses the new one and the new
key names the old one as its predecessor. A verifier that pinned either key reaches the other through this record. The
rotation is recorded as a `key/rotate` evidence entry (the first key as `key/activate`), and the previous key can be
removed from the settings after the restart. Every instance has to restart with the new key.

</p>

<p style="text-align: justify;">

When the old private key is lost, set `LEDGER_SIGNING_UNENDORSED_ROTATION` to `true` instead. The rotation record then
carries only the new key's signature, so a verifier that pinned the old key does not trust the new key, while one that
pins the new key still trusts the old checkpoints. A retired key can never become active again, and the ledger refuses
to start when the configured key changes without one of these two settings.

</p>

```shell
openssl genpkey -algorithm ed25519 -outform DER -out ledger-signing-2.der
export LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY=${LEDGER_SIGNING_PRIVATE_KEY}
export LEDGER_SIGNING_PRIVATE_KEY=$(base64 -w0 ledger-signing-2.der)
export LEDGER_SIGNING_PUBLIC_KEY=$(openssl pkey -inform DER -in ledger-signing-2.der -pubout -outform DER | base64 -w0)
curl http://localhost:8090/api/v1/keys -H "Authorization: Bearer nexusphere-ledger-development-key-change-me"
```

### Keys in Vault

<p style="text-align: justify;">

With `LEDGER_SIGNING_PROVIDER=vault` the ledger never holds its private key. It reads the public keys of every version
of an Ed25519 key in the Vault transit engine, signs with the latest version through `POST /v1/{mount}/sign/{key}`, and
checks at startup that Vault's signature matches the published key. The token comes from
`LEDGER_SIGNING_VAULT_TOKEN`, or from `LEDGER_SIGNING_VAULT_TOKEN_FILE`, which is read again on every call so the Vault
agent can renew it. A rotation in Vault (`vault write -f transit/keys/{key}/rotate`) becomes a ledger rotation at the
next restart, endorsed by the previous version, which Vault still holds. Moving from a local key to Vault is endorsed
when `LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY` holds the local key for that one restart.

</p>

Step 1. Start the stack with Vault signing; the compose file runs Vault in development mode and creates the key:

```shell
LEDGER_SIGNING_PROVIDER=vault docker compose --file ledger/compose.yaml --project-name ledger up -d --build
```

Step 2. Check the keys; the last one must be `ACTIVE` with a rotation endorsed by the development key:

```shell
curl -s http://localhost:8090/api/v1/keys -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" | jq '.[] | {keyId, status, previous: .rotation.previousKeyId}'
```

Step 3. Rotate the key in Vault and restart the ledger; one more key must appear, endorsed by the one before:

```shell
docker exec -e VAULT_TOKEN=nexusphere-vault-development-token vault vault write -f transit/keys/nexusphere-ledger/rotate
docker compose --file ledger/compose.yaml --project-name ledger restart ledger
```

<p style="text-align: justify;">

A retired key never signs again, so going back to `local` after this needs a new local key and its own rotation. The
Vault policy of the ledger's token needs only `read` on `{mount}/keys/{key}` and `update` on `{mount}/sign/{key}`.

</p>

## Key Revocation

<p style="text-align: justify;">

When a retired key leaks, the operator revokes it with the time from which it must be treated as compromised. The
active key signs a revocation record (`nexusphere-ledger/key-revocation/v1`) with the key ID, `compromisedAt`,
`revokedAt`, the reason and the revoker's key ID; the ledger records it as a `key/revoke` evidence entry and shows the
key as `REVOKED`. The active key cannot be revoked: rotate first, so another key can sign the revocation. A revoked key
leaves the public JWKS, so mandates it signed no longer verify.

</p>

<p style="text-align: justify;">

A signature carries no trustworthy time of its own, since whoever holds the leaked key can write any time into it. A
verifier therefore accepts a checkpoint, log checkpoint, statement or receipt of a revoked key only when witness
cosignatures prove that the log checkpoint existed before `compromisedAt`: at least one cosignature, or
`--witnesses-required` of them, with an earlier time. A rotation endorsed by a revoked key at or after `compromisedAt`
gives no trust either, so pin the key that signed the revocation. A revocation counts only when a key the verifier
trusts signed it.

</p>

Step 1. Rotate to a new key, as above, if the leaked key is still active.

Step 2. Revoke the leaked key; `compromisedAt` is the earliest time the key may have been copied:

```shell
curl -s -X POST http://localhost:8090/api/v1/keys/{keyId}/revocation -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"compromisedAt":"2026-10-08T00:00:00Z","reason":"backup copy leaked"}'
```

Step 3. Check the keys; the leaked key must show `REVOKED` with its revocation:

```shell
curl -s http://localhost:8090/api/v1/keys -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" | tee keys.json | jq '.[] | {keyId, status, compromisedAt: .revocation.compromisedAt}'
```

Step 4. Verify an older package with the new key pinned, the key list and the witnesses; it must end with
`Result: VALID` and name the revoked key:

```shell
java -jar ledger/verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar --public-key "$(jq -r '.[] | select(.status == "ACTIVE") | .publicKey' keys.json)" --keys keys.json --witness "{witness verifier key}" package.json
```

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
than `maxUses` times. A reported `FAILED` keeps the use, because the ledger cannot know whether the action ran. The
gateways return the use themselves when they see that the call never ran: the MCP or A2A server could not be reached,
the MCP server rejected the request (HTTP 4xx, or JSON-RPC parse, invalid request, unknown method or invalid params),
or the peer ledger refused the A2A request without a receipt. The outcome entry then carries `grant.useReturned`.

</p>

| Reason code           | Decision | Meaning                                                     |
|-----------------------|----------|-------------------------------------------------------------|
| `ALLOWED_BY_GRANT`    | ALLOW    | An active grant covers the action and the target            |
| `AGENT_NOT_ACTIVE`    | DENY     | The agent is unknown or disabled                            |
| `NO_ACTIVE_GRANT`     | DENY     | The principal has no active grant for this agent            |
| `NOT_COVERED`         | DENY     | Active grants exist, but none covers the action and target  |
| `NOT_YET_VALID`       | DENY     | The covering grant starts later                             |
| `USES_EXHAUSTED`      | DENY     | The covering grant has used all its uses                    |
| `POLICY_DENIED`       | DENY     | A grant covers the action, but the active policies deny it  |
| `APPROVAL_PENDING`    | DENY     | Policies ask for an approval that has not been answered yet |
| `ALLOWED_BY_APPROVAL` | ALLOW    | A grant covers the action and its approval was given        |
| `APPROVAL_DENIED`     | DENY     | The approver denied the approval                            |
| `APPROVAL_EXPIRED`    | DENY     | Nobody answered the approval in time                        |

Agent, grant and decision changes are evidence too: `agent/register`, `agent/disable`, `agent/rotate-key`,
`agent/signing-key`, `grant/create`, `grant/approve`, `grant/deny` and `grant/revoke`, with the owner or the granting
principal as `principalId`.

```shell
curl -X POST http://localhost:8090/api/v1/agents -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","name":"Invoice agent","ownerId":"acme"}'
curl -X POST http://localhost:8090/api/v1/grants -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"principalId":"alice","agentId":"invoice-agent","actions":["tools/call"],"targets":["send_email","read_*"],"expiresAt":"2026-12-31T00:00:00Z","maxUses":100}'
curl -X POST http://localhost:8090/api/v1/decisions -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d '{"principalId":"alice","action":"tools/call","target":"send_email"}'
curl -X POST http://localhost:8090/api/v1/decisions/{decisionId}/outcome -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d '{"outcome":"SUCCEEDED"}'
```

## Policies

<p style="text-align: justify;">

A grant says what a principal lets an agent do; policies say what the operator lets any agent do. The operator
activates a [Cedar](https://www.cedarpolicy.com/) policy set with `PUT /api/v1/policies`, and from then on every
decision, including those of the [MCP gateway](#mcp-gateway) and the [A2A gateway](#a2a-gateway), must be allowed by
both a grant and the policies. When a grant allows an action the policies deny, the decision is `DENY` with
`POLICY_DENIED`, the grant keeps its use, and the reason names the policies that forbid it. A policy that fails to
evaluate denies too. Each policy is named by its `@id` annotation, and two policies with the same name are refused.

</p>

<p style="text-align: justify;">

The principal is `Agent::"<agentId>"`, the action is `Action::"<action>"` and the resource is `Target::"<target>"`.
The context carries `principalId`, `target`, `inputHash` when present, `attributes` as a record of strings, `model`
with its `name` and `version` when present, and the UTC `hour` (0 to 23) and `weekday` (1 for Monday to 7). With
`LEDGER_POLICY_DEFAULT_EFFECT=permit` the ledger adds the policy `ledger-default-permit`, so whatever no policy
forbids is allowed; with `deny` only what a policy permits is allowed. Before a policy set is activated, grants alone
decide.

</p>

<p style="text-align: justify;">

A policy set is addressed by the SHA-256 of its UTF-8 text, at most 1 MiB, and is never changed or deleted;
activating it records `policy/activate` evidence with that hash. Every decision records the attributes `policy.hash`,
`policy.ids` (the policies that decided it) and `policy.defaultEffect`, so a client cannot send attributes starting
with `policy.`. A package, report or dispute carries `policies`, the texts of the policy sets its disclosed entries
name, and the verifier checks that each text matches its hash and is named by a disclosed entry.

</p>

Step 1. Start the ledger as in [Run](#run), then register an agent with a grant for every bank tool; the answers must
show `201`:

```shell
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8090/api/v1/agents -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"payments-agent","name":"Payments agent","ownerId":"acme"}'
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8090/api/v1/grants -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d "{\"principalId\":\"alice\",\"agentId\":\"payments-agent\",\"actions\":[\"tools/call\"],\"targets\":[\"bank/*\"],\"expiresAt\":\"$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ)\"}"
```

Step 2. Write a policy set that forbids wire transfers and invoices other than 100:

```shell
cat > policies.cedar <<'CEDAR'
@id("no-wire-transfers")
forbid(principal, action == Action::"tools/call", resource == Target::"bank/wire_transfer");

@id("small-payments")
forbid(principal, action, resource == Target::"bank/pay_invoice")
  unless { context.attributes has amount && context.attributes.amount == "100" };
CEDAR
```

Step 3. Activate it; the answer must show the same hash as `sha256sum` and both policy names:

```shell
jq -Rs '{text: .}' policies.cedar | curl -s -X PUT http://localhost:8090/api/v1/policies -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d @- | jq -c '{hash, policies, defaultEffect}'
sha256sum policies.cedar
```

Step 4. Ask for three decisions; the answers must show `POLICY_DENIED`, `POLICY_DENIED` and `ALLOWED_BY_GRANT`:

```shell
for request in '"target":"bank/wire_transfer"' '"target":"bank/pay_invoice","attributes":{"amount":"900"}' '"target":"bank/pay_invoice","attributes":{"amount":"100"}'; do
  curl -s -X POST http://localhost:8090/api/v1/decisions -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d "{\"agentId\":\"payments-agent\",\"principalId\":\"alice\",\"action\":\"tools/call\",$request}" | jq -r '.reasonCode'
done
```

Step 5. Export a package and verify it; the output must show
`Policies    : 1 policy set texts match the hashes their entries name` and `Result: VALID`:

```shell
curl -s -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer $LEDGER_API_KEY" > /dev/null
curl -s -X POST http://localhost:8090/api/v1/packages -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"payments-agent"}' > package.json
java -jar verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar package.json
```

### Human Approval

<p style="text-align: justify;">

A `forbid` policy annotated `@approval("principal")`, `@approval("operator")` or `@approval("any")` does not end
the matter: when a grant covers the action and only such policies forbid it, the decision is `DENY` with
`APPROVAL_PENDING` and an `approvalId`, and the ledger waits `LEDGER_APPROVAL_TIMEOUT` for the named approver. The
operator answers with `POST /api/v1/approvals/{id}/approve` or `/deny`, the principal with the same routes under
`/api/v1/principal/approvals` after signing in, or on their own device through OpenID Connect CIBA. When the agent
asks again for the same action, target and input, an approved request is `ALLOW` with `ALLOWED_BY_APPROVAL` and uses
the grant once; a denied one is `APPROVAL_DENIED`; and one nobody answered in time is `APPROVAL_EXPIRED`, so silence
denies. The next request after that asks again. Agents read their own approvals with `GET /api/v1/approvals/{id}`.

</p>

<p style="text-align: justify;">

Every answer is evidence: `approval/approve`, `approval/deny` or `approval/expire` with the attributes `approval.id`,
`approval.action`, `approval.approver`, `approval.method` (`operator`, `principal`, `ciba` or `timeout`), the policy
hash and, for a principal, the `consent.*` attributes of their token. The decision entries carry `approval.id` too,
and a pending one `approval.expiresAt`. With `LEDGER_APPROVAL_WEBHOOK_URL` the ledger posts each new request as JSON
of type `approval.requested`, signed as in [HTTP Message Signatures](#http-message-signatures), until an answer `2xx`
arrives. With `LEDGER_APPROVAL_CIBA_ENDPOINT`, `LEDGER_APPROVAL_CIBA_TOKEN_ENDPOINT`, `LEDGER_APPROVAL_CIBA_CLIENT_ID`
and `LEDGER_APPROVAL_CIBA_CLIENT_SECRET` the ledger starts a CIBA request in poll mode for every request a principal may
answer, with the principal id as `login_hint`, and accepts the returned token only when it is valid for
`LEDGER_OIDC_ISSUER` and names that principal. Pending requests are checked every `LEDGER_APPROVAL_POLL_INTERVAL`.

</p>

Step 1. Start a receiver that prints every webhook it gets:

```shell
python3 -c 'import http.server as h
class R(h.BaseHTTPRequestHandler):
    def do_POST(self):
        print(self.rfile.read(int(self.headers["Content-Length"])).decode(), flush=True); self.send_response(204); self.end_headers()
h.HTTPServer(("localhost", 9100), R).serve_forever()' &
```

Step 2. Start the ledger as in [Run](#run) with `LEDGER_APPROVAL_WEBHOOK_URL=http://localhost:9100/approvals`, then
register the agent and its grant as in Step 1 of [Policies](#policies).

Step 3. Activate a policy that lets the operator approve wire transfers; the answer must list
`wire-needs-operator`:

```shell
cat > approvals.cedar <<'CEDAR'
@id("wire-needs-operator")
@approval("operator")
forbid(principal, action, resource == Target::"bank/wire_transfer");
CEDAR
jq -Rs '{text: .}' approvals.cedar | curl -s -X PUT http://localhost:8090/api/v1/policies -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d @- | jq -c '.policies'
```

Step 4. Ask for a wire transfer; the answer must show `APPROVAL_PENDING` with an `approvalId`, and the receiver must
print an `approval.requested` with the same id:

```shell
WIRE='{"agentId":"payments-agent","principalId":"alice","action":"tools/call","target":"bank/wire_transfer"}'
APPROVAL=$(curl -s -X POST http://localhost:8090/api/v1/decisions -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d "$WIRE" | tee /dev/stderr | jq -r .approvalId)
```

Step 5. Approve it; the answer must show `APPROVED` by the `operator`:

```shell
curl -s -X POST http://localhost:8090/api/v1/approvals/$APPROVAL/approve -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"reason":"Invoice 42 checked"}' | jq -c '{status, method}'
```

Step 6. Ask twice more; the answers must show `ALLOWED_BY_APPROVAL`, then `APPROVAL_PENDING` for a new request:

```shell
for i in 1 2; do curl -s -X POST http://localhost:8090/api/v1/decisions -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d "$WIRE" | jq -r .reasonCode; done
```

## Principal Login and Consent

<p style="text-align: justify;">

Without OIDC, the operator creates grants and the principal's consent is the operator system's job. When
`LEDGER_OIDC_ISSUER` is set, principals sign in with that OpenID Connect provider (Keycloak, Entra ID, Auth0, Okta or
any other) and decide on their own grants. The ledger accepts the provider's access or ID token as a bearer token on
`/api/v1/principal/**` only. It checks the signature with the provider's keys (from the discovery document, or from
`LEDGER_OIDC_JWKS_URI` when the ledger reaches the provider at another address than the issuer), the issuer, the
expiry, and that `LEDGER_OIDC_AUDIENCE` is in `aud` or is the `azp` client. The principal ID is the claim named by
`LEDGER_OIDC_PRINCIPAL_CLAIM`, for example `preferred_username` or `email`. `GET /public/v1/oidc` tells a sign-in
page the issuer and the public client to use, `LEDGER_OIDC_CLIENT_ID` or else the audience.

</p>

<p style="text-align: justify;">

With OIDC on, a grant created by the operator, or asked for by an agent for itself on `POST /api/v1/grants`, starts
as `PENDING`. A pending grant allows nothing and gives no mandate. The principal lists it, then approves it, which
makes it `ACTIVE` with `consent` `PRINCIPAL`, or denies it, which makes it `DENIED`. A principal can also create a grant
on its own behalf, active at once, and revoke its own grants, which revokes their mandates. Each step is evidence
(`grant/create`, `grant/approve`, `grant/deny`, `grant/revoke`) with `consent.issuer`, `consent.subject`,
`consent.authTime` and `consent.token`, the SHA-256 of the token the principal used, so the consent can be traced to
one sign-in at the provider without storing the token.

</p>

```shell
TOKEN=$(curl -s http://localhost:8180/realms/nexusphere/protocol/openid-connect/token -d grant_type=password -d client_id=nexusphere-ledger -d username=alice -d password=alice | jq -r .access_token)
curl -X GET "http://localhost:8090/api/v1/principal/grants?state=PENDING" -H "Authorization: Bearer ${TOKEN}"
curl -X POST http://localhost:8090/api/v1/principal/grants/{id}/approve -H "Authorization: Bearer ${TOKEN}"
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
`files/read_file` covers it. A denied call never reaches the server and is answered with JSON-RPC error `-32003` and the
decision ID and reason code in `error.data`. An allowed call is forwarded, and its outcome is reported from the answer:
a JSON-RPC error, `isError: true`, a non-2xx status or an unreachable server (`-32002`, HTTP 502) count as `FAILED`. The
input hash is the SHA-256 of the request body, the output hash is the SHA-256 of the answer, the MCP session ID is the
correlation ID, and every answer to a decided call carries the `X-Ledger-Decision` header. Other messages, such as
`initialize`, `tools/list` and notifications, pass through unchanged with the `Mcp-Session-Id` header. Every request to
the server carries the ledger's [HTTP message signature](#http-message-signatures).

</p>

<p style="text-align: justify;">

When the client accepts `text/event-stream`, a streamed answer is relayed event by event as it arrives, including the
requests and notifications the server sends inside it (for example `elicitation/create` or progress), and the outcome
is recorded when the stream ends from the event that answers the call, with `mcp.events` (the number of events) and
`mcp.stream` (`COMPLETE` or `BROKEN`) as attributes. A client that accepts only JSON gets the answer as one JSON
response. `GET /mcp/{name}` relays the server-to-client stream with `Mcp-Session-Id` and `Last-Event-ID`, and the
client's answers to server requests pass through on `POST`. A stream stays open for at most `LEDGER_STREAM_TIMEOUT`.
JSON-RPC batches are not supported, because MCP removed them in protocol version 2025-06-18; a batch is answered with
`-32600`.

</p>

```shell
curl -X POST http://localhost:8090/mcp/files -H "Authorization: Bearer {agentApiKey}" -H "X-Ledger-Principal: alice" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/tmp/report.txt"}}}'
```

## Mandates

<p style="text-align: justify;">

A grant lives inside one ledger. When an agent acts for its principal at another organization, it carries a mandate:
a signed, self-contained copy of the grant that the other side can check without calling back. A mandate is an IETF
SD-JWT VC signed with the ledger's Ed25519 key (`alg` `EdDSA`, `typ` `dc+sd-jwt`, `kid` the ledger key ID,
`vct` `urn:nexusphere:vct:agent-mandate:1`): the issuer-signed JWT followed by its disclosures, each ending in `~`.
The operator, or the agent the grant was given to, asks for one with `POST /api/v1/mandates` and the grant ID, an
optional `audience` (the organization that will receive it) and an optional `expiresAt` that may not be later than the
grant's own expiry. Only an active or not-yet-valid grant can be turned into a mandate.

</p>

| Claim                | Meaning                                                                        |
|----------------------|--------------------------------------------------------------------------------|
| `iss`                | The ledger, `LEDGER_MANDATE_ISSUER`                                            |
| `vct`                | `urn:nexusphere:vct:agent-mandate:1`                                           |
| `sub`                | The agent ID                                                                   |
| `aud`                | The receiving organization, when given                                         |
| `jti`                | The mandate ID                                                                 |
| `iat`, `nbf`, `exp`  | Issued, valid from and expiry, in epoch seconds                                |
| `mandate.principal`  | The person or organization the agent acts for (selectively disclosable)        |
| `mandate.actions`    | Allowed actions, exact or with a trailing `*`                                  |
| `mandate.targets`    | Allowed targets, exact or with a trailing `*`                                  |
| `mandate.maxUses`    | The grant's use limit, when it has one                                         |
| `mandate.grant`      | The grant ID (selectively disclosable)                                         |
| `mandate.termsHash`  | The grant terms' SHA-256, as in the grant's evidence (selectively disclosable) |
| `status.status_list` | `idx` and `uri` of the mandate's bit in the status list                        |
| `cnf.jwk`            | The agent's Ed25519 public key, when the agent has registered one              |
| `_sd_alg`            | `sha-256`, the hash of the disclosures listed in `mandate._sd`                 |

<p style="text-align: justify;">

The principal, grant and terms hash are disclosures, so an agent can hand a mandate on without them:
`SdJwt.parse(token).present(Set.of("grant"))` keeps only the grant, and the receiver still checks the signature,
time, audience, coverage and revocation. Actions, targets, the use limit, time and status are always in the signed
JWT and cannot be withheld. The A2A gateway always sends the full mandate, because the receiving ledger records the
principal. A mandate for an agent with a signing key names that key in `cnf` and is presented with a key binding, as
described in [Agent Keys and Key Binding](#agent-keys-and-key-binding).

</p>

<p style="text-align: justify;">

Revocation follows the IETF Token Status List draft. `GET /public/v1/mandates/status` returns a signed
`statuslist+jwt` with one bit per mandate (`1` is revoked), deflated with zlib and base64url encoded in
`status_list.lst`, and an `exp` of `LEDGER_MANDATE_STATUS_LIST_TTL` after it was made. Revoking a mandate sets its bit,
and revoking a grant revokes every mandate issued from it. The keys are published as a JWK set at
`GET /public/v1/keys`. Both public endpoints need no API key. Issuing and revoking a mandate are recorded as evidence
(`mandate/issue` with the SHA-256 of the token as the input hash, and `mandate/revoke`), with the grant as the
delegation ID and the mandate ID as the target.

</p>

```shell
curl -X POST http://localhost:8090/api/v1/mandates -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d '{"grantId":"{grantId}","audience":"https://supplier.example"}'
curl http://localhost:8090/public/v1/keys
curl http://localhost:8090/public/v1/mandates/status
```

### Verifying a Mandate

<p style="text-align: justify;">

An organization that receives a mandate checks it with `MandateVerifier` from `ledger/mandate`, a plain Java library
with no Spring dependency. It trusts only the issuers it is told to trust, reads their keys from
`{issuer}/public/v1/keys`, checks the signature, `nbf` and `exp` with a clock skew (60 seconds by default), the
audience, and, when an action and target are given, that the mandate covers them. It then reads the status list named
in the mandate, which must be served by the issuer, checks its signature, issuer, address and expiry, and looks up the
mandate's bit. Keys and status lists are cached (5 minutes by default, and never past the status list's `exp`). When
the keys or the status list cannot be read the mandate is not valid, so a verifier fails closed. Each problem has a
code: `MALFORMED`, `WRONG_TYPE`, `UNTRUSTED_ISSUER`, `UNKNOWN_KEY`, `BAD_SIGNATURE`, `NOT_YET_VALID`, `EXPIRED`,
`WRONG_AUDIENCE`, `NOT_COVERED`, `REVOKED`, `STATUS_UNAVAILABLE`, `KEY_BINDING_MISSING`, `KEY_BINDING_INVALID` and
`KEY_NOT_BOUND`. `verifyBound(token, nonce)` also checks the key binding against the nonce the verifier expects, and
`requireKeyBinding()` refuses mandates without `cnf`.

</p>

```java
MandateVerifier verifier = MandateVerifier.builder()
        .trustIssuer("https://ledger.acme.example")
        .audience("https://supplier.example")
        .build();
MandateCheck check = verifier.verify(token, "a2a/send", "supplier/orders");
if (!check.valid()) {
    throw new AccessDeniedException(check.problems().toString());
}
String principal = check.claims().principalId();
```

<p style="text-align: justify;">

The verifier jar checks a mandate from the command line. `--public-key` pins the issuer's key instead of reading it
from the issuer, and `--skip-status` leaves out the revocation check for offline use. `--nonce` checks the key binding
against an expected nonce and `--require-key-binding` refuses mandates without `cnf`. The exit codes are the same as
for packages.

</p>

```shell
java -jar ledger/verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar mandate --issuer http://localhost:8090 --audience https://supplier.example --action a2a/send --target supplier/orders mandate.jwt
```

### AP2 and Verifiable Intent

<p style="text-align: justify;">

AP2 and Mastercard's Verifiable Intent, now developed at the FIDO Alliance, carry payment mandates as SD-JWT
credentials too. A Nexusphere mandate is not an AP2 mandate, but its parts line up, so a bridge can translate one
into the other. AP2 signs with ES256 and Nexusphere with EdDSA; both bind mandates to the holder's key with `cnf` and
a KB-JWT.

</p>

| AP2 / Verifiable Intent                    | Nexusphere                                                                    |
|--------------------------------------------|-------------------------------------------------------------------------------|
| Intent mandate signed for the user         | Mandate issued from a grant the principal approved with their own sign-in     |
| User authorization                         | The `grant/approve` evidence of the principal's consent, named by `termsHash` |
| Merchants or payees the intent allows      | `aud` and `mandate.targets`                                                   |
| What the agent may do                      | `mandate.actions`                                                             |
| Intent expiry                              | `exp`, never later than the grant                                             |
| Spending or use limit                      | `mandate.maxUses`, counted by both ledgers                                    |
| Credential status                          | `status.status_list`, the IETF Token Status List                              |
| Cart and payment mandates                  | No equivalent: Nexusphere does not handle payments                            |
| `merchant_authorization` from the merchant | The receipt the receiving ledger signs for an A2A exchange                    |
| Audit trail of the transaction             | Evidence on both ledgers, with SCITT statements and receipts                  |

## Agent Keys and Key Binding

<p style="text-align: justify;">

An API key proves who calls the ledger, but whoever copies it can act as the agent. An agent can therefore register
an Ed25519 public key of its own with `PUT /api/v1/agents/{agentId}/signing-key`; the private key never leaves the
agent. The agent may set its key once, and only the operator can replace it afterwards, so a stolen API key cannot
swap in another key. The ledger records `agent/signing-key` with the key ID and the previous key ID.

</p>

<p style="text-align: justify;">

Every mandate issued for such an agent names its key in `cnf.jwk` (RFC 7800). The agent presents the mandate with a
key binding JWT as in the SD-JWT specification: `typ` `kb+jwt`, signed with its own key, with `iat`, `aud` (the
receiving organization), `nonce` and `sd_hash`, the base64url SHA-256 of the presentation up to its last `~`. A
verifier checks the signature with the key in `cnf`, the `sd_hash`, the audience, the nonce and that `iat` is at most
five minutes old. A mandate with `cnf` but no key binding is `KEY_BINDING_MISSING`; a wrong key, nonce, audience,
`sd_hash` or age, or a key binding on a mandate without `cnf`, is `KEY_BINDING_INVALID`.

</p>

<p style="text-align: justify;">

Once an agent has a signing key, the A2A gateway no longer makes mandates for it. The agent sends its own
presentation in `X-Nexusphere-Mandate` with the peer's issuer as `aud` and the hex SHA-256 of the request body as
`nonce`. The ledger answers HTTP 400 without the header and HTTP 403 with the problems when the presentation does not
hold: a key binding problem, `WRONG_AGENT`, `WRONG_AUDIENCE`, `NOT_COVERED`, `WRONG_PRINCIPAL`, `UNDISCLOSED` when the
principal or grant is withheld, `STALE_KEY` when the mandate names a replaced key, and `GRANT_MISMATCH` when the
decision picked another grant. The receiving ledger checks the key binding with the same body hash, so a presentation
cannot be used for another request, and records the sender's key ID as `a2a.keyBinding`.

</p>

Step 1. Make a key pair for the agent; only the public key goes to the ledger:

```shell
openssl genpkey -algorithm ed25519 -out agent.pem && openssl pkey -in agent.pem -pubout -outform DER | base64 -w0 > agent.pub
```

Step 2. Register the public key with the agent's own API key; the answer shows its `signingKeyId`:

```shell
curl -s -X PUT http://localhost:8090/api/v1/agents/invoice-agent/signing-key -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d "{\"publicKey\":\"$(cat agent.pub)\"}" | jq -r .signingKeyId
```

Step 3. Get a mandate for the grant; it now carries the agent's key in `cnf`:

```shell
curl -s -X POST http://localhost:8090/api/v1/mandates -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d '{"grantId":"{grantId}","audience":"https://supplier.example"}' | jq -r .token > mandate.jwt
```

Step 4. Present it with a key binding from the agent, here with the Python SDK, and check it; it must end with
`Result: VALID` and show the `signingKeyId` from Step 2 as `Agent key`:

```shell
python3 -c "from nexusphere_ledger import PrivateKey, present_bound; from cryptography.hazmat.primitives.serialization import load_pem_private_key as load; print(present_bound(open('mandate.jwt').read().strip(), PrivateKey(load(open('agent.pem', 'rb').read(), None)), 'https://supplier.example', 'ab' * 32))" > presented.jwt
java -jar ledger/verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar mandate --issuer http://localhost:8090 --audience https://supplier.example --nonce "$(python3 -c "print('ab' * 32)")" presented.jwt
```

## Agent Track Records

<p style="text-align: justify;">

Another organization that is about to trust an agent wants to know how it has behaved, without reading the evidence.
`POST /api/v1/agents/{agentId}/track-record`, called by the operator or by the agent itself, answers a signed SD-JWT VC
of type `urn:nexusphere:vct:agent-track-record:1` with `typ` `dc+sd-jwt`. It counts the evidence that names the agent
up to the latest checkpoint: the `total`, the first and last sequence and when the ledger recorded them, and, as
claims the agent may withhold, the `outcomes`, the number of distinct `principals` and the count of each action. It
names the checkpoint `sequence`, its `headHash` and the signed index root of [Index Proofs](#index-proofs), so the
`total` must equal the count an index proof gives for the agent at the same checkpoint. The body takes an optional
`audience` and `validFor`, an ISO-8601 duration between `PT1M` and `P365D`, by default `P30D`. When the agent has a
signing key, the credential names it in `cnf` and must be presented with a key binding as a mandate is. The ledger
records `track-record/issue` with the SHA-256 of the credential.

</p>

<p style="text-align: justify;">

`nexusphere-ledger-verify track-record` checks the credential against the issuer's published keys and, with
`--index-proof`, that a proof for the `agent` term at the same checkpoint verifies and gives the same index root and
counts. A track record is a snapshot: it does not say what happened after its checkpoint, and it is not revoked; it
expires.

</p>

Step 1. Start the ledger as in [Run](#run), register an agent and record two actions under it:

```shell
AGENT_KEY=$(curl -s -X POST http://localhost:8090/api/v1/agents -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"quote-agent","name":"Quote agent","ownerId":"acme"}' | jq -r .apiKey)
curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $AGENT_KEY" -H "Content-Type: application/json" -d '{"agentId":"quote-agent","principalId":"alice","action":"tools/call","outcome":"SUCCEEDED"}' | jq -c '{sequence}'
curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $AGENT_KEY" -H "Content-Type: application/json" -d '{"agentId":"quote-agent","principalId":"bob","action":"tools/call","outcome":"FAILED"}' | jq -c '{sequence}'
```

Step 2. Sign a checkpoint and let the agent ask for its track record for a supplier:

```shell
curl -s -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{sequence}'
curl -s -X POST http://localhost:8090/api/v1/agents/quote-agent/track-record -H "Authorization: Bearer $AGENT_KEY" -H "Content-Type: application/json" -d '{"audience":"https://supplier.example","validFor":"P7D"}' > track-record.json
jq -r .credential track-record.json > track-record.jwt
```

Step 3. Get an index proof for the agent at the same checkpoint:

```shell
curl -s -X POST http://localhost:8090/api/v1/index/proofs -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d "{\"term\":{\"type\":\"agent\",\"agentId\":\"quote-agent\"},\"sequence\":$(jq .checkpoint track-record.json)}" > agent-proof.json
```

Step 4. Verify both as the supplier; the output must show `Entries     : 3`, `Outcomes    : FAILED 1, SUCCEEDED 2`,
`Principals  : 3` and `Result: VALID`:

```shell
java -jar verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar track-record --issuer http://localhost:8090 --audience https://supplier.example --index-proof agent-proof.json track-record.jwt
```

## A2A Gateway

<p style="text-align: justify;">

Two organizations that each run a ledger can let their agents talk over A2A (JSON-RPC over HTTP) with evidence on
both sides that matches entry for entry. The sending ledger names each peer with the URL of the peer's inbound
endpoint and the peer ledger's issuer, for example
`LEDGER_A2A_PEERS_SUPPLIER_URL=https://ledger.supplier.example/a2a/in/sales` and
`LEDGER_A2A_PEERS_SUPPLIER_ISSUER=https://ledger.supplier.example`. The receiving ledger lists the issuers it trusts
in `LEDGER_A2A_TRUSTED_ISSUERS` (comma separated) and names its own agents with their A2A URL and an optional
`Authorization` header, for example `LEDGER_A2A_AGENTS_SALES_URL=http://sales-agent:9000/a2a`.

</p>

<p style="text-align: justify;">

The agent sends its JSON-RPC request to `/a2a/out/{peer}` with its own API key and the `X-Ledger-Principal` header.
The ledger decides action `a2a/send` on target `{peer}/{method}`, so a grant on `supplier/*` covers it. A denied
request never leaves and is answered with JSON-RPC error `-32003`. An allowed request carries two headers:
`X-Nexusphere-Mandate`, a mandate for the grant with the peer's issuer as audience (reused while it is valid for at
least another minute), or the agent's own key-bound presentation when the agent has a signing key, and `X-Nexusphere-Request`, a request proof signed by the sending ledger
(`typ` `nexusphere-a2a-request+jwt`) that binds the mandate, the method, the SHA-256 of the body and the decision ID
as the request ID.

</p>

<p style="text-align: justify;">

The receiving ledger answers on `/a2a/in/{agent}` without an API key. It verifies the mandate with `MandateVerifier`
(trusted issuer, key from the issuer's JWKS, signature, time, its own issuer as audience, the `a2a/send` action, the
revocation status and, for a mandate with `cnf`, the key binding with the body hash as nonce), the sender's [HTTP
message signature](#http-message-signatures) and the request proof (same issuer, key, mandate and agent, its own issuer
as audience, the method, the body hash and an `iat` within `LEDGER_A2A_REQUEST_MAX_AGE`). A request ID is accepted once.
A mandate or proof that cannot be trusted is answered with HTTP 401 and is not recorded. A trusted mandate that is
revoked, expired or for another audience is answered with HTTP 403 and recorded as an `a2a/receive` DENY entry.
Otherwise the request goes to the local agent and one `a2a/receive` entry records it with the same input and output
hashes as the sender, the mandate as delegation ID and the request ID as correlation ID. The answer carries
`X-Nexusphere-Receipt`, a receipt signed by the receiving ledger (`typ` `nexusphere-a2a-receipt+jwt`) with the request
and response hashes, the HTTP status, the outcome, and the sequence and hash of its evidence entry.

</p>

<p style="text-align: justify;">

The sending ledger verifies the receipt with the peer's published key, records the outcome with
`a2a.receipt` set to `VERIFIED`, `INVALID` or `MISSING` and the peer's evidence sequence and hash, and returns the
answer with `X-Ledger-Decision`, `X-Ledger-Exchange`, `X-Ledger-Receipt` and the receipt. Both ledgers keep the
mandate, the request proof and the receipt of every exchange at `GET /api/v1/a2a/exchanges/{id}`, so either side can
later show what the other signed. The receiving ledger also counts the uses of every mandate on its own: once the requests it accepted under a
mandate reach `mandate.maxUses`, it denies the next one with `USES_EXHAUSTED`, so a sender that ignores its own count
gets nowhere. A request that could not reach the receiving agent does not count.

</p>

<p style="text-align: justify;">

Streaming methods (`message/stream`, `tasks/resubscribe`) work the same way. When the agent answers with
server-sent events, both ledgers relay every event as it arrives. The response hash of a stream is the SHA-256 of the
`data` of every event, each followed by a newline, so both sides compute the same value. The exchange fails when an
event is a JSON-RPC error, a task ends `failed` or `rejected`, or the stream breaks. When the stream ends, the
receiving ledger records its entry and sends the receipt as a last event named `nexusphere-receipt`; the sending
ledger takes that event out, verifies the receipt and records the outcome with `a2a.events` and `a2a.stream`, so the
agent sees only the events of the task.

</p>

```shell
curl -X POST http://localhost:8090/a2a/out/supplier -H "Authorization: Bearer {agentApiKey}" -H "X-Ledger-Principal: alice" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":"1","method":"message/send","params":{"message":{"role":"user","messageId":"1","parts":[{"kind":"text","text":"Order 40 pallets"}]}}}'
```

### Reconciling Two Ledgers

<p style="text-align: justify;">

Each ledger holds its own side of every exchange, so two organizations can check that their records agree without
sharing their evidence. `POST /api/v1/a2a/statements` (operator) takes a `peer`, a configured peer name or a trusted
issuer, and a window `from` and `to` of at most 31 days, and answers a statement signed by the ledger
(`typ` `nexusphere-a2a-statement+jwt`) of every exchange with that peer whose request proof was issued in the window:
its direction, request ID, request time, method, mandate ID, request and response hashes, HTTP status, outcome and the
SHA-256 of its receipt. It names no principal, agent or content.

</p>

<p style="text-align: justify;">

The operator sends the statement to the peer by any channel. `POST /api/v1/a2a/reconciliations` takes the peer's
`statement`, checks its signature with the peer's published keys and that it names this ledger, builds this ledger's
own statement for the same window and compares them request by request. An exchange is `NOT_RECEIVED` when the sender
has it and the receiver does not, unless the receiver refused it before recording it (HTTP 401) or could not be
reached; `NOT_SENT` when the receiver holds a request the sender never sent, which only someone with the sender's key
acting around the sender's ledger can cause; and `MISMATCH` with the fields that differ. The answer carries this
ledger's signed statement, and the reconciliation is recorded as `a2a/reconcile` with the hashes of both statements.
`nexusphere-ledger-verify reconcile` compares two statements offline.

</p>

Step 1. Start a stand-in sales agent on port 9000 that accepts every order:

```shell
cat > sales-agent.py <<'EOF'
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

class Agent(BaseHTTPRequestHandler):
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        answer = {"jsonrpc": "2.0", "id": request["id"], "result": {"kind": "message", "role": "agent",
                  "messageId": "a-" + str(request["id"]), "parts": [{"kind": "text", "text": "Order accepted"}]}}
        body = json.dumps(answer).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

HTTPServer(("localhost", 9000), Agent).serve_forever()
EOF
python3 sales-agent.py &
```

Step 2. Start the supplier's ledger on port 8092 with its own database and key, trusting the ledger on port 8090:

```shell
docker run -d --name supplier-ledger-db -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger -e POSTGRES_PASSWORD=ledger -p 5435:5432 postgres:18-alpine && sleep 5
openssl genpkey -algorithm ed25519 -out supplier.pem
LEDGER_PORT=8092 LEDGER_DATABASE_PORT=5435 LEDGER_SIGNING_PRIVATE_KEY=$(openssl pkey -in supplier.pem -outform DER | base64 -w0) LEDGER_SIGNING_PUBLIC_KEY=$(openssl pkey -in supplier.pem -pubout -outform DER | base64 -w0) LEDGER_A2A_TRUSTED_ISSUERS=http://localhost:8090 LEDGER_A2A_AGENTS_SALES_URL=http://localhost:9000 java -jar ledger/server/target/server-1.0.0-SNAPSHOT-exec.jar --spring.profiles.active=postgresql,dev > supplier-ledger.log 2>&1 &
until curl -sf http://localhost:8092/actuator/health > /dev/null; do sleep 1; done
```

Step 3. Start the buyer's ledger of [Run](#run) with the supplier as its peer:

```shell
LEDGER_PROFILES=postgresql,dev LEDGER_DATABASE_PORT=5433 LEDGER_A2A_PEERS_SUPPLIER_URL=http://localhost:8092/a2a/in/sales LEDGER_A2A_PEERS_SUPPLIER_ISSUER=http://localhost:8092 mvn -pl ledger/server spring-boot:start
```

Step 4. Register a buying agent, grant it `supplier/*` for alice and send two orders; the grant must be `ACTIVE`
and both orders must answer `Order accepted`:

```shell
AGENT_KEY=$(curl -s -X POST http://localhost:8090/api/v1/agents -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"buyer-agent","name":"Buyer agent","ownerId":"acme"}' | jq -r .apiKey)
curl -s -X POST http://localhost:8090/api/v1/grants -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d "{\"principalId\":\"alice\",\"agentId\":\"buyer-agent\",\"actions\":[\"a2a/send\"],\"targets\":[\"supplier/*\"],\"expiresAt\":\"$(date -u -d '+30 days' +%Y-%m-%dT00:00:00Z)\"}" | jq -r .status
for id in 1 2; do curl -s -X POST http://localhost:8090/a2a/out/supplier -H "Authorization: Bearer $AGENT_KEY" -H "X-Ledger-Principal: alice" -H "Content-Type: application/json" -d "{\"jsonrpc\":\"2.0\",\"id\":\"$id\",\"method\":\"message/send\",\"params\":{\"message\":{\"role\":\"user\",\"messageId\":\"$id\",\"parts\":[{\"kind\":\"text\",\"text\":\"Order 40 pallets\"}]}}}" | jq -r '.result.parts[0].text'; done
```

Step 5. Let the buyer's ledger sign its statement of the exchanges with the supplier from an hour ago to an hour
from now; it must count `2`:

```shell
FROM=$(date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%SZ) TO=$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)
curl -s -X POST http://localhost:8090/api/v1/a2a/statements -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d "{\"peer\":\"supplier\",\"from\":\"$FROM\",\"to\":\"$TO\"}" > buyer-statement.json
jq -r .statement buyer-statement.json > buyer.jwt && jq .exchanges buyer-statement.json
```

Step 6. Let the supplier's ledger reconcile it with its own; it must show `"reconciled":true`, `"matched":2` and no
discrepancies:

```shell
curl -s -X POST http://localhost:8092/api/v1/a2a/reconciliations -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d "{\"statement\":\"$(cat buyer.jwt)\"}" > reconciliation.json
jq -c '{reconciled, matched, discrepancies}' reconciliation.json && jq -r .statement reconciliation.json > supplier.jwt
```

Step 7. Compare both statements offline, as an auditor holding them; it must end with `Result: RECONCILED`:

```shell
java -jar ledger/verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar reconcile buyer.jwt supplier.jwt
```

Step 8. Stop the supplier's ledger, its database and the sales agent:

```shell
kill %1 %2
docker rm -f supplier-ledger-db
```

## HTTP Message Signatures

<p style="text-align: justify;">

Every request the ledger sends on, to an MCP server, to a peer ledger or to a local A2A agent, carries an RFC 9421
HTTP message signature made with the ledger's signing key, so the receiver can check that the ledger sent it and that
nothing changed on the way. The signature covers `@method`, `@authority` and `@path`, and, when there is a body, the
RFC 9530 `Content-Digest` (`sha-256`). Its parameters are `created`, `expires` (five minutes later), a random `nonce`,
`keyid` (the ledger key ID, as in `/public/v1/keys`), `alg` `ed25519` and `tag` `nexusphere-ledger`, under the label
`nexusphere`:

</p>

```text
Content-Digest: sha-256=:J5ADMGzC1HOdzr0zMO+g9eAsXJv5yEPKc6lRWCUkdvg=:
Signature-Input: nexusphere=("@method" "@authority" "@path" "content-digest");created=1790841600;expires=1790841900;nonce="c2lnbmF0dXJlLW5vbmNl";keyid="06e3fd8fda29bb60";alg="ed25519";tag="nexusphere-ledger"
Signature: nexusphere=:buTaWUXE/3h2wCIFRT7OWe2BLf1qwyqqz+5uHPOwjK/X4BsVeQ2CoHxhnCAQzwfEFquoEZdqxEaNFsoI98xlAQ==:
```

<p style="text-align: justify;">

A receiving ledger requires the signature on `/a2a/in/{agent}`: it reads the key from the issuer named in the mandate
and answers HTTP 401 with problem `HTTP_SIGNATURE` when the signature is missing, stale, made with an unknown key, or
does not match the method, address or body. Behind a proxy, the address the receiver sees must be the one the sender
called, so the proxy has to pass the original `Host` or `X-Forwarded-Host` and port. An MCP server or agent checks the
signature with `HttpSignatures.verify` from `ledger/mandate`, `verify_request` from the Python SDK or `verifyRequest`
from the TypeScript SDK, with the keys from `/public/v1/keys`, and can keep the nonces it has seen to refuse replays.

</p>

Step 1. Save a stand-in MCP server that checks each request with the Python SDK as `check_signature.py`:

```python
from http.server import BaseHTTPRequestHandler, HTTPServer
from nexusphere_ledger import JwksKeys, verify_request
from nexusphere_ledger.mandate import http_fetcher

keys = JwksKeys(http_fetcher())

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        url = "http://%s%s" % (self.headers["Host"], self.path)
        verified = verify_request("POST", url, dict(self.headers), body, lambda kid: keys.resolve("http://localhost:8090", kid))
        print("verified", verified.key_id, flush=True)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"jsonrpc":"2.0","id":1,"result":{"content":[]}}')

HTTPServer(("localhost", 3001), Handler).serve_forever()
```

Step 2. Start it, then start the ledger with `LEDGER_MCP_SERVERS_CHECK_URL=http://localhost:3001/mcp` set:

```shell
python3 check_signature.py
```

Step 3. Call a tool through the ledger with an agent that has a grant on `check/*`; the stand-in server must print
`verified` and the ledger's active key ID from `/api/v1/keys`:

```shell
curl -s -X POST http://localhost:8090/mcp/check -H "Authorization: Bearer {agentApiKey}" -H "X-Ledger-Principal: alice" -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{}}}'
```

## Model and Prompt

<p style="text-align: justify;">

Evidence names the model the agent ran in `model`: its `name`, its `version`, the `promptHash` of its system prompt and
the `configHash` of its configuration, such as temperature and tools, each a lowercase SHA-256. The content hash covers
`model`, so the model of an action cannot change afterwards, and a change of model, version or prompt shows up
between two entries. Entries without a model hash as they did before. A [compliance profile](#compliance-profiles) can
require `model`, `modelVersion`, `promptHash` or `configHash`. Through the [MCP gateway](#mcp-gateway) and the
[A2A gateway](#a2a-gateway) the agent sends the headers `X-Ledger-Model`, `X-Ledger-Model-Version`,
`X-Ledger-Prompt-Hash` and `X-Ledger-Config-Hash`; `POST /api/v1/decisions` takes the same `model` object as
evidence, and the ledger records it with the decision and its outcome.

</p>

<p style="text-align: justify;">

The hash alone proves which prompt ran only to someone who has the text. `PUT /api/v1/prompts` stores a prompt or
configuration text, addressed by the SHA-256 of its UTF-8 bytes, and answers its `hash`; storing the same text again
changes nothing. An agent or the operator may store texts, only the operator reads them with
`GET /api/v1/prompts/{hash}`, and the ledger never changes or deletes them. With `"includePrompts": true` a package,
report or dispute carries `prompts`, the stored texts named by its disclosed entries, and the verifier checks that
each text matches its hash and is named by a disclosed entry.

</p>

Step 1. Start the ledger as in [Run](#run).

Step 2. Store the system prompt; the answer must show the same hash as `sha256sum`:

```shell
PROMPT='You pay approved invoices of Acme up to 500 EUR.'
curl -s -X PUT http://localhost:8090/api/v1/prompts -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d "{\"text\":\"$PROMPT\"}" | jq -r .hash
printf '%s' "$PROMPT" | sha256sum
```

Step 3. Record an action with its model and sign a checkpoint; the answer must show the model:

```shell
PROMPT_HASH=$(printf '%s' "$PROMPT" | sha256sum | cut -d' ' -f1)
curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d "{\"agentId\":\"payments-agent\",\"principalId\":\"alice\",\"action\":\"tools/call\",\"target\":\"bank/pay_invoice\",\"decision\":\"ALLOW\",\"outcome\":\"SUCCEEDED\",\"model\":{\"name\":\"claude-sonnet-5-5\",\"version\":\"2026-09-01\",\"promptHash\":\"$PROMPT_HASH\"}}" | jq -c '{sequence, model}'
curl -s -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{sequence}'
```

Step 4. Export a package with the prompt texts; the answer must show the prompt:

```shell
curl -s -X POST http://localhost:8090/api/v1/packages -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"payments-agent","includePrompts":true}' > package.json
jq -c '.prompts' package.json
```

Step 5. Verify the package; the output must show
`Prompts     : 1 prompt texts match the hashes their entries name` and `Result: VALID`:

```shell
java -jar verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar package.json
```

## Compliance Profiles

<p style="text-align: justify;">

The ledger knows no law itself. The rules of a country or region live in a compliance profile, a JSON file that says
how long each kind of evidence is kept at least and at most, whether personal data can be erased on request and how
fast, where the ledger data may be kept, which evidence fields are required, which RFC 3161 timestamp authorities are
accepted and which [report templates](#reports-and-dispute-packages) apply. `LEDGER_COMPLIANCE_PROFILES` lists the
active profiles, `baseline` by default. When several are active the strictest rule wins: the longest minimum and the
shortest maximum retention, the shortest erasure deadline, the regions and timestamp authorities all of them allow,
and every field any of them requires. A profile is a starting point for your own legal review, not legal advice. The
ledger ships three:

</p>

| Profile    | Retention | Erasure on request | Region | Required fields       | Reports                                             |
|------------|-----------|--------------------|--------|-----------------------|-----------------------------------------------------|
| `baseline` | none      | no                 | any    | none                  | `dispute`                                           |
| `eu`       | 6 months  | within 1 month     | `eea`  | `target`, `inputHash` | `dispute`, `dora-ict-records`, `eu-ai-act-logging`  |
| `us`       | 6 years   | within 45 days     | any    | `target`              | `dispute`, `finra-supervision`, `sec-17a-4-records` |

<p style="text-align: justify;">

Your own profile is a file `{id}.json` in `LEDGER_COMPLIANCE_PROFILE_DIRECTORY`; adding a jurisdiction needs no change
to the ledger. A required field is one of `target`, `reason`, `delegationId`, `inputHash`, `outputHash`,
`correlationId`, `occurredAt`, `model`, `modelVersion`, `promptHash` and `configHash`, or an attribute as
`attributes.{name}`; evidence an agent records without it is
refused with HTTP 400. When a profile limits the region, `LEDGER_COMPLIANCE_REGION` must name where the ledger and its
database run, and the ledger does not start when it is missing or not allowed; the ledger trusts what it is told and
cannot see where its servers really are. Every checkpoint signs the id and SHA-256 digest of the canonical JSON of each
active profile, so a package shows which rules were in force, and each change of the active profiles or region is
recorded as `compliance/activate` evidence by the agent `ledger`. `GET /public/v1/compliance` returns the active
profiles with their digests and the combined rules.

</p>

Step 1. Save your own profile as `profiles/uk.json`:

```json
{
  "id": "uk",
  "version": 1,
  "name": "United Kingdom",
  "description": "Our own reading of UK GDPR and FCA record keeping.",
  "retention": [{"actions": "*", "minimum": "P5Y"}],
  "erasure": {"onRequest": true, "deadline": "P1M"},
  "residency": ["uk", "eea"],
  "requiredFields": ["target", "attributes.model"],
  "timestampAuthorities": [],
  "reports": ["dispute"]
}
```

Step 2. Start the ledger with the EU profile and your own:

```shell
export LEDGER_COMPLIANCE_PROFILES=eu,uk
export LEDGER_COMPLIANCE_PROFILE_DIRECTORY=$PWD/profiles
export LEDGER_COMPLIANCE_REGION=eea
```

Step 3. Read the combined rules; the result must show `"residency":["eea"]`, retention `P5Y` and the required fields
of both profiles:

```shell
curl -s http://localhost:8090/public/v1/compliance | jq -c .effective
```

Step 4. Record evidence without the required fields; the answer must be HTTP 400 naming `target`, `inputHash` and
`attributes.model` with the profiles that require them:

```shell
curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","principalId":"alice","action":"tools/call","outcome":"SUCCEEDED"}'
```

Step 5. Sign a checkpoint; the result must have `format` `nexusphere-ledger/checkpoint/v2` and the `eu` and `uk`
profiles with the digests from Step 3:

```shell
curl -s -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{format, profiles}'
```

## Erasure

<p style="text-align: justify;">

`POST /api/v1/principals/{principalId}/erasure` erases what the ledger holds about a principal, by crypto-shredding.
For each entry whose retention under the active [compliance profiles](#compliance-profiles) has passed, the encrypted
values and salts are deleted; an entry still inside its retention, or of a principal under a [legal
hold](#retention-and-legal-holds), keeps them and the answer says how many and until when. The ledger continues such a
pending erasure on its own once the retention ends and the hold is released. When nothing is retained the principal's
data key is destroyed and the principal's name in grants, decisions, mandates and A2A exchanges becomes
`erased:{principalRef}`; a later request for the same principal answers HTTP 404. The chain, the transparency log,
every checkpoint and every package stay valid, because the hash of each entry covers only the commitments of its
personal fields. A principal with an `ACTIVE` or `PENDING` grant is refused with HTTP 409 `GRANTS_OPEN`, so revoke or
deny the grants first. Each erasure is recorded as `principal/erase` evidence by the agent `ledger`, naming only the
principal reference. Backups hold the data until they expire, so keep them no longer than the erasure deadline of your
profiles; see [Operations](../docs/operations.md).

</p>

Step 1. Record evidence for the principal `carol`:

```shell
curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","principalId":"carol","action":"tools/call","target":"read_invoice","outcome":"SUCCEEDED","attributes":{"email":"carol@example.com"}}' | jq -r .id > entry.txt
```

Step 2. Erase `carol`; with the `baseline` profile the answer must show `"erasedEntries":1` and `"completed":true`:

```shell
curl -s -X POST http://localhost:8090/api/v1/principals/carol/erasure -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"reason":"request of the principal"}' | jq -c .
```

Step 3. Read the entry; it must show `"erased":true` with `principalId`, `target` and `attributes` gone and its
commitments kept:

```shell
curl -s http://localhost:8090/api/v1/evidence/$(cat entry.txt) -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{erased, principalId, target, attributes, commitments}'
```

Step 4. Verify the chain; the answer must show `"valid":true`:

```shell
curl -s http://localhost:8090/api/v1/verification -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{valid, checkedEntries}'
```

## Retention and Legal Holds

<p style="text-align: justify;">

A retention rule of a [compliance profile](#compliance-profiles) can set a `maximum` next to its `minimum`, an
ISO-8601 period no shorter than the minimum. Every `LEDGER_COMPLIANCE_RETENTION_INTERVAL`, one hour by default, the
ledger erases the personal fields of each entry older than the shortest maximum of the active profiles for its action,
by the same crypto-shredding as an [erasure](#erasure), records how many as `retention/expire` evidence and continues
the pending erasures whose retention has ended. `POST /api/v1/retention/sweep` runs the same pass at once. Profiles
whose maximum for an action is shorter than another profile's minimum cannot be combined, and the ledger does not
start.

</p>

<p style="text-align: justify;">

A legal hold keeps everything about a principal for a dispute, an investigation or a court order, whatever the
profiles say: while a hold is active, neither an erasure nor the retention pass deletes anything about that principal.
`POST /api/v1/legal-holds` places one with a required `reason`, `POST /api/v1/legal-holds/{id}/release` releases it,
and both are recorded as `legal-hold/place` and `legal-hold/release` evidence by the agent `ledger`. The Privacy page
of the web UI does the same.

</p>

Step 1. Save a profile that keeps evidence at most a year as `profiles/short.json`:

```json
{
  "id": "short",
  "version": 1,
  "name": "Short",
  "retention": [{"actions": "*", "minimum": "P1D", "maximum": "P1Y"}]
}
```

Step 2. Start the ledger with it:

```shell
export LEDGER_COMPLIANCE_PROFILES=short
export LEDGER_COMPLIANCE_PROFILE_DIRECTORY=$PWD/profiles
```

Step 3. Record evidence from 2019 for `dave` and `erin`, and place a legal hold on `erin`:

```shell
for p in dave erin; do curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","principalId":"'$p'","action":"tools/call","target":"read_invoice","outcome":"SUCCEEDED","occurredAt":"2019-01-01T00:00:00Z"}' | jq -r .id > $p.txt; done
curl -s -X POST http://localhost:8090/api/v1/legal-holds -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"principalId":"erin","reason":"dispute 42"}' | jq -r .id > hold.txt
```

Step 4. Run the retention pass; the answer must show `"expiredEntries":1`:

```shell
curl -s -X POST http://localhost:8090/api/v1/retention/sweep -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c .
```

Step 5. Read both entries; `dave` must show `"erased":true` and `erin` `"erased":false`:

```shell
for p in dave erin; do curl -s http://localhost:8090/api/v1/evidence/$(cat $p.txt) -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{erased, principalId}'; done
```

Step 6. Release the hold and run the pass again; the answer must show `"expiredEntries":1` for `erin`:

```shell
curl -s -X POST http://localhost:8090/api/v1/legal-holds/$(cat hold.txt)/release -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"reason":"dispute settled"}' | jq -c '{releasedAt, releaseReason}'
curl -s -X POST http://localhost:8090/api/v1/retention/sweep -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c .
```

## Checkpoint Timestamps

<p style="text-align: justify;">

A signed checkpoint says when it was made, but only by the ledger's own clock. With `LEDGER_TIMESTAMP_URL` set to an
RFC 3161 timestamp authority, the ledger asks that authority to timestamp the SHA-256 of every checkpoint's signed
bytes, and the token proves to anyone that the checkpoint, and so every entry under it, existed at that time.
`LEDGER_TIMESTAMP_CERTIFICATES` is a PEM file with the authority's certificate chain: the ledger keeps only tokens that
chain to it and publishes it at `GET /public/v1/timestamp-authority`. When the authority cannot be reached, the
checkpoint is signed anyway, and the scheduler timestamps it on a later round.

</p>

<p style="text-align: justify;">

The token travels as `timestamp` in the checkpoint of an evidence package, next to `timestampedAt` in the checkpoint
API. Every verifier checks it offline: the token must be for this checkpoint, its signature and the signer's
certificate must hold, and a token more than five minutes older than the checkpoint is rejected.
`--timestamp-certificates` pins the authority's chain and `--require-timestamp` rejects a checkpoint without a token.
When the [compliance profiles](#compliance-profiles) list `timestampAuthorities`, the ledger does not start without
`LEDGER_TIMESTAMP_URL` or with an authority that is not listed.

</p>

Step 1. Install the Sigstore timestamp authority and start it; its signing chain is made in memory at every start:

```shell
go install github.com/sigstore/timestamp-authority/v2/cmd/timestamp-server@v2.1.4
timestamp-server serve --host 127.0.0.1 --port 3004 &
```

Step 2. Save its certificate chain; the answer must show `3`:

```shell
curl -s http://127.0.0.1:3004/api/v1/timestamp/certchain > tsa-chain.pem
grep -c BEGIN tsa-chain.pem
```

Step 3. Start the ledger with the authority:

```shell
export LEDGER_TIMESTAMP_URL=http://127.0.0.1:3004/api/v1/timestamp
export LEDGER_TIMESTAMP_CERTIFICATES=$PWD/tsa-chain.pem
```

Step 4. Record evidence and sign a checkpoint; the answer must show a `timestampedAt`:

```shell
curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","principalId":"alice","action":"tools/call","target":"read_invoice","outcome":"SUCCEEDED"}' | jq -c '{sequence}'
curl -s -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{sequence, timestampedAt}'
```

Step 5. Export a package and verify it with the authority pinned; the output must show
`Timestamp   : ... by CN=Test TSA Timestamping,O=local (pinned)` and `Result: VALID`:

```shell
curl -s -X POST http://localhost:8090/api/v1/packages -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent"}' > package.json
java -jar verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar --timestamp-certificates tsa-chain.pem --require-timestamp package.json
```

## Rekor Anchoring

<p style="text-align: justify;">

A timestamp proves when a checkpoint existed, but only the ledger and its readers hold the checkpoint. With
`LEDGER_REKOR_URL` set to a Rekor v2 log, such as the public Sigstore log or a private one, the ledger also writes
every checkpoint to that log as a `hashedrekord` entry: the SHA-512 of the checkpoint's signed bytes, signed with the
ledger key. Anyone can then find the checkpoint in a log the operator does not run, and a ledger that rewrote its
history would have to explain two different checkpoints for the same sequence. `LEDGER_REKOR_KEY` is the verifier key
of the log: the ledger keeps only entries whose log checkpoint that key signs, and publishes both at
`GET /public/v1/rekor`. When the log cannot be reached, the checkpoint is signed anyway, and the scheduler anchors it
on a later round.

</p>

<p style="text-align: justify;">

The entry travels as `rekor` in the checkpoint of an evidence package, next to `rekorLogIndex` in the checkpoint API.
Every verifier checks it offline: the entry must be for this checkpoint's bytes and this checkpoint's key, the
inclusion proof must lead to the root of the log checkpoint, and `--rekor-key` pins the log that must have signed it.
`--require-rekor` rejects a checkpoint without an entry. Rekor v2 records no time of its own, so anchoring goes with
[checkpoint timestamps](#checkpoint-timestamps) rather than replacing them. Rekor needs Ed25519ph signatures, which
Vault cannot make, so the ledger does not start with `LEDGER_SIGNING_PROVIDER=vault` and `LEDGER_REKOR_URL` together.

</p>

Step 1. Install a Rekor v2 log that keeps its tiles on disk, and make its signing key:

```shell
go install github.com/sigstore/rekor-tiles/v2/cmd/rekor-server/posix@v2.3.0
cp "$(go env GOPATH)/bin/posix" rekor-server
openssl genpkey -algorithm ed25519 -out rekor-key.pem
```

Step 2. Start the log as `rekor.local`:

```shell
./rekor-server serve --http-port 3005 --grpc-port 3006 --http-metrics-port 2115 --hostname rekor.local --storage-dir rekor-log --signer-filepath rekor-key.pem --checkpoint-interval 1s &
```

Step 3. Build the verifier key of the log from its public key; the answer must start with `rekor.local+`:

```shell
REKOR_PUBLIC=$(openssl pkey -in rekor-key.pem -pubout -outform DER | tail -c 32 | base64 -w0)
REKOR_ID=$( (printf 'rekor.local\n\001'; echo "$REKOR_PUBLIC" | base64 -d) | openssl dgst -sha256 -binary | head -c 4 | od -An -tx1 | tr -d ' \n')
REKOR_KEY="rekor.local+$REKOR_ID+$( (printf '\001'; echo "$REKOR_PUBLIC" | base64 -d) | base64 -w0)"
echo "$REKOR_KEY"
```

Step 4. Start the ledger with the log, then ask which log it anchors in; the answer must show the same key:

```shell
export LEDGER_REKOR_URL=http://127.0.0.1:3005
export LEDGER_REKOR_KEY=$REKOR_KEY
curl -s http://localhost:8090/public/v1/rekor | jq -c
```

Step 5. Record evidence and sign a checkpoint; the answer must show a `rekorLogIndex`:

```shell
curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","principalId":"alice","action":"tools/call","target":"read_invoice","outcome":"SUCCEEDED"}' | jq -c '{sequence}'
curl -s -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{sequence, rekorLogIndex}'
```

Step 6. Export a package and verify it with the log pinned; the output must show
`Rekor       : entry 0 in rekor.local (pinned)` and `Result: VALID`:

```shell
curl -s -X POST http://localhost:8090/api/v1/packages -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent"}' > package.json
java -jar verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar --rekor-key "$REKOR_KEY" --require-rekor package.json
```

## Reports and Dispute Packages

<p style="text-align: justify;">

Each [compliance profile](#compliance-profiles) names the report templates that apply to it, and
`GET /api/v1/reports` lists those of the active profiles. `POST /api/v1/reports/{id}` exports an evidence package for
the scope in the body, verifies it with the timestamp authority and Rekor log the ledger uses, and maps what the
evidence shows to the requirements of the template. Every section names a requirement and its source, says
`SHOWN`, `PARTLY_SHOWN` or `NOT_SHOWN`, and lists the findings and entry sequences behind it. A report maps evidence to
requirements; it is not legal advice and not a certification of compliance.

</p>

| Template            | Profiles | Sections                                                                          |
|---------------------|----------|-----------------------------------------------------------------------------------|
| `dispute`           | all      | Incident timeline, authority, outcomes, model, integrity, what the package shows  |
| `eu-ai-act-logging` | `eu`     | EU AI Act Articles 12(1), 12(2), 12(2)(a), 12(3), 19(1) with 26(6), and integrity |
| `dora-ict-records`  | `eu`     | DORA Articles 9(3), 9(4)(c), 17 and 28(3)                                         |
| `finra-supervision` | `us`     | FINRA Rule 3110(a), 3110(b) and the exceptions for review                         |
| `sec-17a-4-records` | `us`     | SEC Rule 17a-4(b), 17a-4(f)(2) for storage and time, and 17a-4(f)(3) for the copy |

<p style="text-align: justify;">

The report is canonical JSON in the format `nexusphere-ledger/report/v1`, signed with the ledger key over everything
but `signature`. It names the checkpoint, head hash, scope and a digest of the hashes of the entries it covers, so a
verifier checks it against the package it came from: `nexusphere-ledger-verify report --package package.json
report.json`. With `?format=html` the same report comes as one page for reading and printing. A profile that names a
template the ledger does not have stops the ledger from starting.

</p>

<p style="text-align: justify;">

`POST /api/v1/disputes` takes the scope with a `title` and an optional `description` of one incident and answers a
ZIP archive for an insurer, arbitrator or court: the evidence package, the signed dispute report in JSON and HTML, the
signing keys with their rotations and revocations, the compliance profiles, and, when the ledger uses them, the
timestamp authority's chain, the Rekor log and the witness keys. `nexusphere-ledger-verify dispute dispute.zip` checks
all of it at once. The keys and logs in the archive come from the ledger itself, so pin the ledger's key with
`--public-key` from a copy obtained on your own.

</p>

Step 1. Start the ledger with the `eu` and `us` profiles:

```shell
export LEDGER_COMPLIANCE_PROFILES=eu,us
export LEDGER_COMPLIANCE_REGION=eea
```

Step 2. List the reports the profiles offer; the answer must show the five templates:

```shell
curl -s http://localhost:8090/api/v1/reports -H "Authorization: Bearer $LEDGER_API_KEY" | jq -r '.[].id'
```

Step 3. Record an action and sign a checkpoint:

```shell
curl -s -X POST http://localhost:8090/api/v1/evidence -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"payments-agent","principalId":"alice","action":"tools/call","target":"bank/pay_invoice","inputHash":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08","decision":"ALLOW","outcome":"SUCCEEDED"}' | jq -c '{sequence}'
curl -s -X POST http://localhost:8090/api/v1/checkpoints -H "Authorization: Bearer $LEDGER_API_KEY" | jq -c '{sequence}'
```

Step 4. Create the EU AI Act report and its package; the answer must list the sections with their status:

```shell
curl -s -X POST http://localhost:8090/api/v1/reports/eu-ai-act-logging -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"payments-agent"}' > report.json
curl -s -X POST http://localhost:8090/api/v1/packages -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"payments-agent"}' > package.json
jq -r '.sections[] | "\(.reference): \(.status)"' report.json
```

Step 5. Verify the report against its package; the output must end with `Result: VALID` twice:

```shell
java -jar verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar report --package package.json report.json
```

Step 6. Create a dispute package and verify it with the ledger's key pinned; the output must show
`Report      : dispute (Dispute package)` and `Result: VALID`:

```shell
curl -s -X POST http://localhost:8090/api/v1/disputes -H "Authorization: Bearer $LEDGER_API_KEY" -H "Content-Type: application/json" -d '{"agentId":"payments-agent","title":"Invoice 4711 paid twice"}' -o dispute.zip
LEDGER_PUBLIC_KEY=$(curl -s http://localhost:8090/api/v1/keys -H "Authorization: Bearer $LEDGER_API_KEY" | jq -r '.[] | select(.status == "ACTIVE") | .publicKey')
java -jar verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar dispute --public-key "$LEDGER_PUBLIC_KEY" dispute.zip
```

## Conformance Vectors

<p style="text-align: justify;">

[Conformance Vectors](conformance/README.md) hold fixed inputs and outputs for every format the ledger produces, made
with the published RFC 8032 test keys: canonical JSON, the evidence chain and checkpoints, Merkle roots and proofs,
signed notes and cosignatures, SCITT statements and receipts, an SD-JWT VC mandate, a key-bound mandate with its
KB-JWT, an HTTP message signature, a checkpoint that signs compliance profiles, an RFC 3161 token from a real
timestamp authority, a Rekor entry from a real Rekor v2 log and a signed dispute report, an entry that names its model and prompt, and index proofs of presence and absence. Another implementation checks itself against them, and `ConformanceVectorsTest` in `ledger/verifier` fails when the ledger's own output changes.

</p>

## API

All `/api/**`, `/mcp/**` and `/a2a/out/**` endpoints require `Authorization: Bearer {LEDGER_API_KEY}` or an agent
API key, except `/api/v1/principal/**`, which requires a principal token from the OIDC provider and answers
`403 FORBIDDEN` to everyone else. Operator-only endpoints answer `403 FORBIDDEN` to agents.

| Method | Path                                       | Description                                                                                             |
|--------|--------------------------------------------|---------------------------------------------------------------------------------------------------------|
| POST   | `/api/v1/evidence`                         | Record an entry with the fields the compliance profiles require; `201` with `Location`                  |
| POST   | `/api/v1/evidence/batch`                   | Record up to 500 entries in order as one run, all or none; an item error names its `index`              |
| GET    | `/api/v1/evidence/{id}`                    | Get an evidence entry                                                                                   |
| GET    | `/api/v1/evidence/{id}/statement`          | The entry as a SCITT signed statement (`application/cose`)                                              |
| GET    | `/api/v1/evidence/{id}/receipt`            | RFC 9942 receipt for the entry at `treeSize`, by default the latest log checkpoint                      |
| GET    | `/api/v1/evidence`                         | List entries by `agentId`, `principalId`, `after` and `limit` (max 500)                                 |
| GET    | `/api/v1/ledger/head`                      | Current sequence and hash                                                                               |
| POST   | `/api/v1/checkpoints`                      | Sign the current head now; `409 LEDGER_EMPTY` when nothing is recorded (operator)                       |
| GET    | `/api/v1/checkpoints/latest`               | Latest checkpoint, with its RFC 3161 `timestamp`, `timestampedAt` and Rekor `rekorLogIndex` when set    |
| GET    | `/api/v1/checkpoints`                      | List checkpoints by `after` and `limit`                                                                 |
| GET    | `/api/v1/log/proofs/inclusion`             | Inclusion proof for a `sequence` in the log at `treeSize`, by default the latest log checkpoint         |
| GET    | `/api/v1/log/proofs/consistency`           | Consistency proof from `firstSize` to `secondSize`, by default the latest log checkpoint                |
| GET    | `/api/v1/keys`                             | Signing keys with their status, rotation and revocation records                                         |
| POST   | `/api/v1/keys/{keyId}/revocation`          | Revoke a retired key as compromised from `compromisedAt`, with a `reason` (operator)                    |
| POST   | `/api/v1/packages`                         | Export a package by `agentId`, `principalId`, `fromSequence`, `toSequence`; `includePrompts` (operator) |
| POST   | `/api/v1/packages/verify`                  | Verify a package with the verifier library, pinned to `publicKey` when given (operator)                 |
| GET    | `/api/v1/reports`                          | The report templates of the active compliance profiles (operator)                                       |
| POST   | `/api/v1/reports/{id}`                     | A signed report over the package for the scope in the body; HTML with `format=html` (operator)          |
| POST   | `/api/v1/disputes`                         | A ZIP dispute package for the scope, `title` and `description` in the body (operator)                   |
| PUT    | `/api/v1/policies`                         | Activate a Cedar policy set `text` and answer its `hash`, policy names and default effect (operator)    |
| GET    | `/api/v1/policies`                         | The active policy set (operator)                                                                        |
| GET    | `/api/v1/policies/{hash}`                  | A stored policy set by its hash (operator)                                                              |
| GET    | `/api/v1/approvals`                        | Approvals by `status`, `agentId`, `principalId` and `limit` (operator, or the agent for its own)        |
| GET    | `/api/v1/approvals/{id}`                   | An approval with its status (operator or its agent)                                                     |
| POST   | `/api/v1/approvals/{id}/approve`           | Approve with an optional `reason` (operator, when the policy allows)                                    |
| POST   | `/api/v1/approvals/{id}/deny`              | Deny with an optional `reason` (operator, when the policy allows)                                       |
| GET    | `/api/v1/principal/approvals`              | The signed-in principal's approvals by `status` and `limit`                                             |
| POST   | `/api/v1/principal/approvals/{id}/approve` | Approve as the signed-in principal, with consent evidence                                               |
| POST   | `/api/v1/principal/approvals/{id}/deny`    | Deny as the signed-in principal, with consent evidence                                                  |
| PUT    | `/api/v1/prompts`                          | Store a prompt or configuration `text` and answer its `hash`; `201` when new (agent or operator)        |
| GET    | `/api/v1/prompts/{hash}`                   | A stored prompt text with when and by whom it was stored (operator)                                     |
| POST   | `/api/v1/index/proofs`                     | A signed proof that a `term` occurs, with its count, or never does, up to a checkpoint (operator)       |
| GET    | `/api/v1/verification`                     | Full verification report (operator)                                                                     |
| POST   | `/api/v1/agents`                           | Register an agent and return its API key once (operator)                                                |
| GET    | `/api/v1/agents`                           | List agents by `after` and `limit` (operator)                                                           |
| GET    | `/api/v1/agents/{agentId}`                 | Get an agent                                                                                            |
| POST   | `/api/v1/agents/{agentId}/disable`         | Disable an agent and its key (operator)                                                                 |
| POST   | `/api/v1/agents/{agentId}/key`             | Issue a new API key; the old one stops working (operator)                                               |
| PUT    | `/api/v1/agents/{agentId}/signing-key`     | Register the agent's Ed25519 `publicKey`; the agent sets it once, the operator can replace it           |
| POST   | `/api/v1/agents/{agentId}/track-record`    | A signed SD-JWT VC of the agent's evidence counts up to the latest checkpoint (operator or the agent)   |
| POST   | `/api/v1/grants`                           | Create a grant (operator), or ask for one for itself (agent, only with OIDC)                            |
| GET    | `/api/v1/grants`                           | List grants by `agentId`, `principalId`, `after` and `limit`                                            |
| GET    | `/api/v1/grants/{id}`                      | Get a grant with its status and uses                                                                    |
| POST   | `/api/v1/grants/{id}/revoke`               | Revoke a grant with an optional `reason` (operator)                                                     |
| POST   | `/api/v1/principals/{principalId}/erasure` | Erase the principal's personal data past retention, with an optional `reason` (operator)                |
| POST   | `/api/v1/legal-holds`                      | Place a legal hold on a `principalId` with a `reason` (operator)                                        |
| GET    | `/api/v1/legal-holds`                      | List legal holds, only the active ones with `active=true` (operator)                                    |
| GET    | `/api/v1/legal-holds/{id}`                 | Get a legal hold (operator)                                                                             |
| POST   | `/api/v1/legal-holds/{id}/release`         | Release a legal hold with a `reason` (operator)                                                         |
| POST   | `/api/v1/retention/sweep`                  | Erase what the profiles no longer allow and continue pending erasures now (operator)                    |
| GET    | `/api/v1/principal/evidence`               | The evidence about the principal by `after` and `limit`                                                 |
| GET    | `/api/v1/principal`                        | The signed-in principal                                                                                 |
| GET    | `/api/v1/principal/grants`                 | List the principal's grants by `state`, `after` and `limit`                                             |
| GET    | `/api/v1/principal/grants/{id}`            | Get one of the principal's grants                                                                       |
| POST   | `/api/v1/principal/grants`                 | Create a grant on the principal's own behalf; returns `201`                                             |
| POST   | `/api/v1/principal/grants/{id}/approve`    | Approve a pending grant                                                                                 |
| POST   | `/api/v1/principal/grants/{id}/deny`       | Deny a pending grant with an optional `reason`                                                          |
| POST   | `/api/v1/principal/grants/{id}/revoke`     | Revoke one of the principal's grants with an optional `reason`                                          |
| POST   | `/api/v1/decisions`                        | Decide an action for an agent and record it; returns `201`                                              |
| GET    | `/api/v1/decisions/{id}`                   | Get a decision and its outcome                                                                          |
| POST   | `/api/v1/decisions/{id}/outcome`           | Report `SUCCEEDED` or `FAILED` once for an allowed decision                                             |
| POST   | `/api/v1/mandates`                         | Issue a signed mandate from a grant; returns `201`                                                      |
| GET    | `/api/v1/mandates`                         | List the mandates of a `grantId`                                                                        |
| GET    | `/api/v1/mandates/{id}`                    | Get a mandate, its token and status                                                                     |
| POST   | `/api/v1/mandates/{id}/revoke`             | Revoke a mandate with an optional `reason` (operator)                                                   |
| GET    | `/public/v1/keys`                          | Active and retired signing keys as a JWK set, no API key                                                |
| GET    | `/public/v1/mandates/status`               | Signed mandate status list, no API key                                                                  |
| GET    | `/public/v1/log/checkpoint`                | Latest log checkpoint as a signed note with its cosignatures, no API key                                |
| GET    | `/public/v1/log/key`                       | Verifier key of the log, no API key                                                                     |
| GET    | `/public/v1/witness/key`                   | Verifier key this ledger cosigns with as a witness, no API key                                          |
| POST   | `/public/v1/witness/add-checkpoint`        | C2SP witness endpoint: cosign a watched log's checkpoint, `404` when it watches none, no API key        |
| GET    | `/public/v1/compliance`                    | Active compliance profiles with their digests and the combined rules, no API key                        |
| GET    | `/public/v1/timestamp-authority`           | The RFC 3161 timestamp authority and its certificate chain in PEM, no API key                           |
| GET    | `/public/v1/rekor`                         | The Rekor log the ledger anchors checkpoints in and its verifier key, no API key                        |
| GET    | `/public/v1/oidc`                          | The OIDC issuer and client for principal sign-in, `404` without OIDC                                    |
| POST   | `/mcp/{server}`                            | MCP gateway: decide, forward and record a `tools/call`; forward other messages                          |
| GET    | `/mcp/{server}`                            | Relay the MCP server-to-client stream                                                                   |
| DELETE | `/mcp/{server}`                            | Close an MCP session on the server                                                                      |
| POST   | `/a2a/out/{peer}`                          | A2A gateway: decide, attach a mandate and request proof, forward and record with the peer's receipt     |
| POST   | `/a2a/in/{agent}`                          | A2A inbound: verify the mandate and proof, forward to the agent, record and sign a receipt; no API key  |
| GET    | `/api/v1/a2a/exchanges/{id}`               | Get an A2A exchange with its mandate, request proof and receipt                                         |
| POST   | `/api/v1/a2a/statements`                   | A signed statement of the exchanges with a `peer` in a window `from` to `to` (operator)                 |
| POST   | `/api/v1/a2a/reconciliations`              | Reconcile a peer's signed `statement` with this ledger's own and record the result (operator)           |
| GET    | `/actuator/health`                         | Health, open for probes                                                                                 |

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
