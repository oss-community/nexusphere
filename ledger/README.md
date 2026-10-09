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
* [Evidence Packages](#evidence-packages)
* [Key Rotation](#key-rotation)
* [Agents, Grants and Decisions](#agents-grants-and-decisions)
* [Principal Login and Consent](#principal-login-and-consent)
* [MCP Gateway](#mcp-gateway)
* [Mandates](#mandates)
* [A2A Gateway](#a2a-gateway)
* [Nexusphere Core](#nexusphere-core)
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

## Modules

| Module                                                  | Responsibility                                                                                               |
|---------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| [`ledger/chain`](chain/README.md)                       | Canonical JSON, evidence entry, hash chain verifier, Ed25519 keys and checkpoints. No framework dependencies |
| [`ledger/mandate`](mandate/README.md)                   | Mandate tokens, status lists, JWK keys and the mandate verifier SDK for other organizations                  |
| [`ledger/server`](server/README.md)                     | Spring Boot service: evidence API, PostgreSQL storage, checkpoint scheduler, verification                    |
| [`ledger/verifier`](verifier/README.md)                 | Command-line verifier for evidence packages and mandates, with no server dependency                          |
| [`ledger/demo-mcp`](demo-mcp/README.md)                 | Demo MCP server with file and mail tools, and the demo script                                                |
| [`ledger/ledger-e2e-tests`](ledger-e2e-tests/README.md) | End-to-end tests against the server and PostgreSQL                                                           |

## Getting Started

### Prerequisites

* [Java 21](https://www.oracle.com/java/technologies/downloads)
* [Maven 3](https://maven.apache.org/index.html)
* [Docker](https://www.docker.com)
* [jq](https://jqlang.org) for the demo script

### Dockerized

```shell
mvn -pl ledger/server,ledger/verifier,ledger/demo-mcp -am package -DskipTests=true
docker compose --file ledger/compose.yaml --project-name ledger up -d --build
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
| `prometheus`        | http://localhost:9090     | Prometheus with the ledger and core alerts       |
| `alertmanager`      | http://localhost:9093     | Alertmanager, no channel until one is set        |

<p style="text-align: justify;">

Keycloak imports the `nexusphere` realm from `ledger/keycloak/nexusphere-realm.json` with the public client
`nexusphere-ledger` and the principals `alice` and `bob`, whose passwords are their names. The ledger in the compose
file trusts this realm, so grants created by the operator wait for the principal's approval.

</p>

<p style="text-align: justify;">

The web UI on http://localhost:5173 opens with the operator key or a Keycloak sign-in; it is described in
[Nexusphere Frontend](../frontend/README.md). The MCP addresses have no web page. `/mcp/demo` on the ledger and `/mcp` on `demo-mcp` accept only MCP JSON-RPC
`POST` requests, and the gateway also needs an agent API key, so opening them in a browser shows nothing. Run the
demo script below to see calls go through the gateway.

</p>

### Demo

```shell
ledger/demo-mcp/demo.sh
```

<p style="text-align: justify;">

The script registers an agent, asks `alice` to grant it `read_file` and `send_email` on the demo server, signs `alice`
in with Keycloak to approve the grant when the ledger asks for consent, calls both
through the gateway, has `delete_file` denied, lists the recorded evidence, exports an evidence package, verifies it
offline with the ledger's published key and shows that a changed entry fails verification. `LEDGER_URL`,
`LEDGER_API_KEY`, `KEYCLOAK_URL`, `VERIFIER_JAR` and `WORK_DIR` override its defaults.

</p>

```shell
docker compose --file ledger/compose.yaml --project-name ledger down
docker volume prune -f
```

### Kubernetes

<p style="text-align: justify;">

`ledger/kube-dev.yaml` runs the same services as the compose file in the `dev` namespace, next to the core from the
root `kube-dev.yaml`. The Keycloak realm comes from the `keycloak-realm` ConfigMap. Keycloak and the UI are reached
on the same local ports as with compose, so the sign-in redirect and the token issuer stay
`http://localhost:8180` and `http://localhost:5173`.

</p>

```shell
mvn -pl ledger/server,ledger/verifier,ledger/demo-mcp -am package -DskipTests=true
docker compose --file ledger/compose.yaml --project-name ledger build
kubectl apply -f ledger/kube-dev.yaml
kubectl get all -n dev
kubectl port-forward service/ledger 8090:8090 -n dev
kubectl port-forward service/keycloak 8180:8080 -n dev
kubectl port-forward service/ledger-ui 5173:80 -n dev
kubectl port-forward service/ledger-adminer 8092:8080 -n dev
```

```shell
kubectl set env deployment/application -n dev APP_LEDGER_URL=http://ledger:8090 APP_LEDGER_API_KEY=nexusphere-ledger-development-key-change-me
```

```shell
kubectl delete -f ledger/kube-dev.yaml
```

### Run

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

### Verify

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
LEDGER_SIGNING_PRIVATE_KEY:
LEDGER_SIGNING_PUBLIC_KEY:
LEDGER_SIGNING_PREVIOUS_PRIVATE_KEY:
LEDGER_SIGNING_UNENDORSED_ROTATION: false
LEDGER_CHECKPOINT_INTERVAL: 1m
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
```

<p style="text-align: justify;">

`LEDGER_API_KEY` and the signing keys have no default outside the `dev` profile, and the ledger refuses to start with
the published development secrets unless `dev` is active. The private key is a base64 PKCS#8 Ed25519 key and the public
key is its base64 X.509 encoding; the ledger refuses to start when they do not match. The tables live in the `ledger`
schema, so the ledger can share a database with the core. Any setting can also come from a file of the same name in
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
| `hash`          | SHA-256 of the canonical JSON of `format`, `sequence`, `previousHash` and `contentHash`                       |

<p style="text-align: justify;">

The content hash is the SHA-256 of the canonical JSON of `format` and every field except `sequence`, `previousHash`
and `hash`. The link (`sequence`, `previousHash`, `contentHash`, `hash`) can be checked without the content, so a
package can keep entries private and still prove the chain. Canonical JSON has sorted keys, no whitespace and `null` for absent fields. Timestamps are UTC with microseconds, for
example `2026-10-01T08:00:00.123456Z`. A checkpoint signs the canonical JSON of `format`
(`nexusphere-ledger/checkpoint/v1`), `sequence`, `headHash`, `createdAt` and `keyId`. The key ID is the first 16 hex
characters of the SHA-256 of the X.509 public key.

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

## Evidence Packages

<p style="text-align: justify;">

`POST /api/v1/packages` exports an evidence package for an auditor, a court or a counterparty. The request selects
evidence by `agentId`, `principalId`, `fromSequence` and `toSequence`. The package (`nexusphere-ledger/package/v1`)
holds the public keys with their rotation records, an anchor checkpoint (the last checkpoint before the first selected
entry, or genesis), an end checkpoint (the first checkpoint at or after the last selected entry, created at the head
when none exists yet) and every link between them. Only selected links carry their full entry; all others carry only
their link, so nothing outside the selection is disclosed. A package holds at most 200,000 links. Its `log` field
carries the log checkpoint at the end checkpoint's sequence with every cosignature the ledger holds, and an inclusion
proof for each disclosed entry.

</p>

<p style="text-align: justify;">

The verifier needs no server and no database. It checks the signatures of both checkpoints, recomputes every link from
the anchor to the end checkpoint, checks every disclosed entry against its content hash and the scope of the package,
and exits with `0` for a valid package, `1` for an invalid one and `2` for a usage error. Pass the ledger's public key
from `GET /api/v1/keys` (or a key published elsewhere) with `--public-key` so the verifier does not trust the key inside
the package. When the package has a `log`, the verifier also checks that the log checkpoint is signed by the ledger,
covers the end checkpoint and proves every disclosed entry. Pass each witness's verifier key with `--witness` and the
number of cosignatures needed with `--witnesses-required` (all given witnesses by default) to require that independent
witnesses saw the same log.

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
  Witnesses   : partner.example
Result: VALID
```

## Key Rotation

<p style="text-align: justify;">

The ledger keeps every signing key it has used. Checkpoints, mandates, status lists and A2A receipts are signed with the
active key, and everything signed with a retired key still verifies, because each signature names its key and the
retired keys stay published in `GET /api/v1/keys`, `GET /public/v1/keys` and every evidence package.

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

| Reason code        | Decision | Meaning                                                    |
|--------------------|----------|------------------------------------------------------------|
| `ALLOWED_BY_GRANT` | ALLOW    | An active grant covers the action and the target           |
| `AGENT_NOT_ACTIVE` | DENY     | The agent is unknown or disabled                           |
| `NO_ACTIVE_GRANT`  | DENY     | The principal has no active grant for this agent           |
| `NOT_COVERED`      | DENY     | Active grants exist, but none covers the action and target |
| `NOT_YET_VALID`    | DENY     | The covering grant starts later                            |
| `USES_EXHAUSTED`   | DENY     | The covering grant has used all its uses                   |

Agent, grant and decision changes are evidence too: `agent/register`, `agent/disable`, `agent/rotate-key`,
`grant/create`, `grant/approve`, `grant/deny` and `grant/revoke`, with the owner or the granting principal as
`principalId`.

```shell
curl -X POST http://localhost:8090/api/v1/agents -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent","name":"Invoice agent","ownerId":"acme"}'
curl -X POST http://localhost:8090/api/v1/grants -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"principalId":"alice","agentId":"invoice-agent","actions":["tools/call"],"targets":["send_email","read_*"],"expiresAt":"2026-12-31T00:00:00Z","maxUses":100}'
curl -X POST http://localhost:8090/api/v1/decisions -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d '{"principalId":"alice","action":"tools/call","target":"send_email"}'
curl -X POST http://localhost:8090/api/v1/decisions/{decisionId}/outcome -H "Authorization: Bearer {agentApiKey}" -H "Content-Type: application/json" -d '{"outcome":"SUCCEEDED"}'
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
`files/read_file` covers it. A denied call never reaches the server and is answered with JSON-RPC error `-32003` and
the decision ID and reason code in `error.data`. An allowed call is forwarded, and its outcome is reported from the
answer: a JSON-RPC error, `isError: true`, a non-2xx status or an unreachable server (`-32002`, HTTP 502) count as
`FAILED`. The input hash is the SHA-256 of the request body, the output hash is the SHA-256 of the answer, the MCP
session ID is the correlation ID, and every answer to a decided call carries the `X-Ledger-Decision` header. Other
messages, such as `initialize`, `tools/list` and notifications, pass through unchanged with the `Mcp-Session-Id`
header.

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
a signed, self-contained copy of the grant that the other side can check without calling back. A mandate is a compact
JWS signed with the ledger's Ed25519 key (`alg` `EdDSA`, `typ` `nexusphere-mandate+jwt`, `kid` the ledger key ID).
The operator, or the agent the grant was given to, asks for one with `POST /api/v1/mandates` and the grant ID, an
optional `audience` (the organization that will receive it) and an optional `expiresAt` that may not be later than the
grant's own expiry. Only an active or not-yet-valid grant can be turned into a mandate.

</p>

| Claim                | Meaning                                                                     |
|----------------------|-----------------------------------------------------------------------------|
| `iss`                | The ledger, `LEDGER_MANDATE_ISSUER`                                         |
| `sub`                | The agent ID                                                                |
| `aud`                | The receiving organization, when given                                      |
| `jti`                | The mandate ID                                                              |
| `iat`, `nbf`, `exp`  | Issued, valid from and expiry, in epoch seconds                             |
| `mandate.principal`  | The person or organization the agent acts for                               |
| `mandate.actions`    | Allowed actions, exact or with a trailing `*`                               |
| `mandate.targets`    | Allowed targets, exact or with a trailing `*`                               |
| `mandate.maxUses`    | The grant's use limit, when it has one                                      |
| `mandate.grant`      | The grant ID                                                                |
| `mandate.termsHash`  | The SHA-256 of the grant terms, the same hash the evidence of the grant has |
| `status.status_list` | `idx` and `uri` of the mandate's bit in the status list                     |

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
`WRONG_AUDIENCE`, `NOT_COVERED`, `REVOKED` and `STATUS_UNAVAILABLE`.

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
from the issuer, and `--skip-status` leaves out the revocation check for offline use. The exit codes are the same as
for packages.

</p>

```shell
java -jar ledger/verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar mandate --issuer http://localhost:8090 --audience https://supplier.example --action a2a/send --target supplier/orders mandate.jwt
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
least another minute), and `X-Nexusphere-Request`, a request proof signed by the sending ledger
(`typ` `nexusphere-a2a-request+jwt`) that binds the mandate, the method, the SHA-256 of the body and the decision ID
as the request ID.

</p>

<p style="text-align: justify;">

The receiving ledger answers on `/a2a/in/{agent}` without an API key. It verifies the mandate with `MandateVerifier`
(trusted issuer, key from the issuer's JWKS, signature, time, its own issuer as audience, the `a2a/send` action and the
revocation status) and the request proof (same issuer, key, mandate and agent, its own issuer as audience, the method,
the body hash and an `iat` within `LEDGER_A2A_REQUEST_MAX_AGE`). A request ID is accepted once. A mandate or proof
that cannot be trusted is answered with HTTP 401 and is not recorded. A trusted mandate that is revoked, expired or for
another audience is answered with HTTP 403 and recorded as an `a2a/receive` DENY entry. Otherwise the request goes to
the local agent and one `a2a/receive` entry records it with the same input and output hashes as the sender, the
mandate as delegation ID and the request ID as correlation ID. The answer carries `X-Nexusphere-Receipt`, a receipt
signed by the receiving ledger (`typ` `nexusphere-a2a-receipt+jwt`) with the request and response hashes, the HTTP
status, the outcome, and the sequence and hash of its evidence entry.

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

## Nexusphere Core

<p style="text-align: justify;">

The Nexusphere core can feed this ledger. With `APP_LEDGER_URL` and `APP_LEDGER_API_KEY` (the operator key) set on
the core, its authorization decisions, delegation changes and other domain events arrive as evidence through an
outbox, each delegation becomes a grant from the delegator to the delegate, and suspending or revoking the delegation
revokes the grant. The delegate asks the core, not the ledger, for a mandate. Agent and principal ids are the core's
principal ids. When this ledger runs with principal sign-in, grants created by the core wait for the principal's
approval, and `LEDGER_OIDC_PRINCIPAL_CLAIM` must name a claim that holds the core principal id. Details are in [Nexusphere Core Integration](../core/integration/README.md).

</p>

## API

All `/api/**`, `/mcp/**` and `/a2a/out/**` endpoints require `Authorization: Bearer {LEDGER_API_KEY}` or an agent
API key, except `/api/v1/principal/**`, which requires a principal token from the OIDC provider and answers
`403 FORBIDDEN` to everyone else. Operator-only endpoints answer `403 FORBIDDEN` to agents.

| Method | Path                                    | Description                                                                                            |
|--------|-----------------------------------------|--------------------------------------------------------------------------------------------------------|
| POST   | `/api/v1/evidence`                      | Record an evidence entry; returns `201` with `Location`                                                |
| POST   | `/api/v1/evidence/batch`                | Record up to 500 entries in order as one run, all or none; an item error names its `index`             |
| GET    | `/api/v1/evidence/{id}`                 | Get an evidence entry                                                                                  |
| GET    | `/api/v1/evidence`                      | List entries by `agentId`, `principalId`, `after` and `limit` (max 500)                                |
| GET    | `/api/v1/ledger/head`                   | Current sequence and hash                                                                              |
| POST   | `/api/v1/checkpoints`                   | Sign the current head now; `409 LEDGER_EMPTY` when nothing is recorded (operator)                      |
| GET    | `/api/v1/checkpoints/latest`            | Latest checkpoint                                                                                      |
| GET    | `/api/v1/checkpoints`                   | List checkpoints by `after` and `limit`                                                                |
| GET    | `/api/v1/log/proofs/inclusion`          | Inclusion proof for a `sequence` in the log at `treeSize`, by default the latest log checkpoint        |
| GET    | `/api/v1/log/proofs/consistency`        | Consistency proof from `firstSize` to `secondSize`, by default the latest log checkpoint               |
| GET    | `/api/v1/keys`                          | Signing keys with their status and rotation records                                                    |
| POST   | `/api/v1/packages`                      | Export an evidence package by `agentId`, `principalId`, `fromSequence` and `toSequence` (operator)     |
| POST   | `/api/v1/packages/verify`               | Verify a package with the verifier library, pinned to `publicKey` when given (operator)                |
| GET    | `/api/v1/verification`                  | Full verification report (operator)                                                                    |
| POST   | `/api/v1/agents`                        | Register an agent and return its API key once (operator)                                               |
| GET    | `/api/v1/agents`                        | List agents by `after` and `limit` (operator)                                                          |
| GET    | `/api/v1/agents/{agentId}`              | Get an agent                                                                                           |
| POST   | `/api/v1/agents/{agentId}/disable`      | Disable an agent and its key (operator)                                                                |
| POST   | `/api/v1/agents/{agentId}/key`          | Issue a new API key; the old one stops working (operator)                                              |
| POST   | `/api/v1/grants`                        | Create a grant (operator), or ask for one for itself (agent, only with OIDC)                           |
| GET    | `/api/v1/grants`                        | List grants by `agentId`, `principalId`, `after` and `limit`                                           |
| GET    | `/api/v1/grants/{id}`                   | Get a grant with its status and uses                                                                   |
| POST   | `/api/v1/grants/{id}/revoke`            | Revoke a grant with an optional `reason` (operator)                                                    |
| GET    | `/api/v1/principal/evidence`            | The evidence about the principal by `after` and `limit`                                                |
| GET    | `/api/v1/principal`                     | The signed-in principal                                                                                |
| GET    | `/api/v1/principal/grants`              | List the principal's grants by `state`, `after` and `limit`                                            |
| GET    | `/api/v1/principal/grants/{id}`         | Get one of the principal's grants                                                                      |
| POST   | `/api/v1/principal/grants`              | Create a grant on the principal's own behalf; returns `201`                                            |
| POST   | `/api/v1/principal/grants/{id}/approve` | Approve a pending grant                                                                                |
| POST   | `/api/v1/principal/grants/{id}/deny`    | Deny a pending grant with an optional `reason`                                                         |
| POST   | `/api/v1/principal/grants/{id}/revoke`  | Revoke one of the principal's grants with an optional `reason`                                         |
| POST   | `/api/v1/decisions`                     | Decide an action for an agent and record it; returns `201`                                             |
| GET    | `/api/v1/decisions/{id}`                | Get a decision and its outcome                                                                         |
| POST   | `/api/v1/decisions/{id}/outcome`        | Report `SUCCEEDED` or `FAILED` once for an allowed decision                                            |
| POST   | `/api/v1/mandates`                      | Issue a signed mandate from a grant; returns `201`                                                     |
| GET    | `/api/v1/mandates`                      | List the mandates of a `grantId`                                                                       |
| GET    | `/api/v1/mandates/{id}`                 | Get a mandate, its token and status                                                                    |
| POST   | `/api/v1/mandates/{id}/revoke`          | Revoke a mandate with an optional `reason` (operator)                                                  |
| GET    | `/public/v1/keys`                       | Active and retired signing keys as a JWK set, no API key                                               |
| GET    | `/public/v1/mandates/status`            | Signed mandate status list, no API key                                                                 |
| GET    | `/public/v1/log/checkpoint`             | Latest log checkpoint as a signed note with its cosignatures, no API key                               |
| GET    | `/public/v1/log/key`                    | Verifier key of the log, no API key                                                                    |
| GET    | `/public/v1/witness/key`                | Verifier key this ledger cosigns with as a witness, no API key                                         |
| POST   | `/public/v1/witness/add-checkpoint`     | C2SP witness endpoint: cosign a watched log's checkpoint, `404` when it watches none, no API key       |
| GET    | `/public/v1/oidc`                       | The OIDC issuer and client for principal sign-in, `404` without OIDC                                   |
| POST   | `/mcp/{server}`                         | MCP gateway: decide, forward and record a `tools/call`; forward other messages                         |
| GET    | `/mcp/{server}`                         | Relay the MCP server-to-client stream                                                                  |
| DELETE | `/mcp/{server}`                         | Close an MCP session on the server                                                                     |
| POST   | `/a2a/out/{peer}`                       | A2A gateway: decide, attach a mandate and request proof, forward and record with the peer's receipt    |
| POST   | `/a2a/in/{agent}`                       | A2A inbound: verify the mandate and proof, forward to the agent, record and sign a receipt; no API key |
| GET    | `/api/v1/a2a/exchanges/{id}`            | Get an A2A exchange with its mandate, request proof and receipt                                        |
| GET    | `/actuator/health`                      | Health, open for probes                                                                                |

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
