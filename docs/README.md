# <p align="center">Nexusphere Project Description</p>

<p align="center">The problem the Nexusphere Ledger solves, its concepts, guarantees, architecture and standards.</p>

## <p align="center">Table of Content</p>

* [Problem](#problem)
* [Concepts](#concepts)
* [Guarantees](#guarantees)
* [Actors](#actors)
* [How It Works](#how-it-works)
* [Architecture](#architecture)
* [Standards](#standards)
* [Out of Scope](#out-of-scope)

## Problem

<p style="text-align: justify;">

AI agents now read files, send email, place orders and talk to agents of other companies on behalf of people. When
something goes wrong, three questions come up: what did the agent do, for whom, and who allowed it. Platform logs
answer them only for whoever runs the platform, and that party can change its own logs. An auditor, a regulator, the
person the agent acted for or a company on the other side of an exchange has to take the operator's word.

</p>

<p style="text-align: justify;">

The Nexusphere Ledger turns every agent action into evidence that anyone can check without trusting the operator. It
is self-hosted and sits beside the agent platforms and gateways an organization already uses, such as Amazon Bedrock
AgentCore, Google Agent Gateway, Microsoft Entra Agent ID or agentgateway; it keeps the proof of what they decided.
Where nothing decides yet, its own MCP and A2A gateways decide and record.

</p>

## Concepts

| Concept          | Meaning                                                                                                      |
|------------------|--------------------------------------------------------------------------------------------------------------|
| Agent            | An identity with its own API key; the program that uses the key is the agent                                 |
| Principal        | The person or organization the agent acts for, such as `alice`                                               |
| Operator         | Whoever runs the ledger and holds its operator key                                                           |
| Grant            | What a principal lets an agent do: actions, targets, validity and a use limit                                |
| Consent          | The principal's approval of a grant with their own sign-in at an OpenID Connect provider                     |
| Decision         | ALLOW or DENY for one action, with a reason code, followed by the reported outcome                           |
| Evidence entry   | One record in the hash chain: who, what, for whom, the decision and the outcome                              |
| Checkpoint       | The chain head signed with the ledger key, so later changes are detectable                                   |
| Transparency log | A Merkle tree over all entries whose signed checkpoints prove that an entry is in the log                    |
| Witness          | An independent party that cosigns log checkpoints and refuses a log that rewrites its history                |
| Signed statement | One entry as an IETF SCITT COSE statement, with a receipt that proves it is in the log                       |
| Package          | Selected entries with the proof that they belong to the signed chain and log, for an auditor or counterparty |
| Mandate          | A signed SD-JWT VC an agent carries to another organization to prove what it may do                          |
| Receipt          | The signed answer of a receiving ledger in an A2A exchange, so both sides hold matching evidence             |

## Guarantees

| ID  | Guarantee                                                                                                   |
|-----|-------------------------------------------------------------------------------------------------------------|
| G-1 | Evidence is append-only: the database refuses any change or deletion, and the hash chain shows any change   |
| G-2 | Every checkpoint is signed; a retired key stays published, so everything it signed still verifies           |
| G-3 | A package verifies offline with the ledger's public key, without the server                                 |
| G-4 | Witnesses refuse a smaller or forked log, so a ledger cannot hide or rewrite entries they have seen         |
| G-5 | A decision is allowed only by an active grant that covers the action and target; the default is DENY        |
| G-6 | A grant created by the operator or an agent allows nothing until the principal approves it, when OIDC is on |
| G-7 | Grant, consent, mandate and key changes are evidence too                                                    |
| G-8 | A receiving ledger accepts an A2A request only with a valid mandate, and both sides record the exchange     |
| G-9 | The ledger refuses to start with the published development secrets outside the `dev` profile                |

## Actors

| Actor               | Does                                                                                      |
|---------------------|-------------------------------------------------------------------------------------------|
| Operator            | Runs the ledger, registers agents, creates grants, exports packages                       |
| Agent               | Asks for decisions, reports outcomes, or calls tools and agents through the gateways      |
| Principal           | Signs in, approves, denies, creates and revokes grants, and reads the evidence about them |
| Gateway or platform | Sends the decisions it made and their outcomes to the ledger as evidence                  |
| Auditor             | Verifies packages, statements and receipts offline                                        |
| Counterparty ledger | Checks mandates of incoming A2A requests and signs receipts                               |
| Witness             | Cosigns log checkpoints of the logs it watches                                            |

## How It Works

1. The operator registers an agent; the agent gets its own API key.
2. A grant names the principal, the agent, the actions, the targets, the validity and the use limit.
3. With principal sign-in on, the principal approves the grant in the web UI.
4. The agent calls a tool through the MCP gateway, or an external gateway decides and sends the decision as evidence.
5. The ledger decides against the grant, forwards only allowed calls, and records the decision and the outcome.
6. Each entry is linked to the previous one by its hash and added to the Merkle log.
7. At a fixed interval the ledger signs the chain head and the log checkpoint, and witnesses cosign the checkpoint.
8. An auditor receives a package of selected entries and verifies it offline with the ledger's public key.
9. For another organization, the agent carries a mandate; the receiving ledger checks it and answers with a receipt.

## Architecture

<p style="text-align: justify;">

The ledger is a Maven multi-module project in `ledger/`. The formats live in plain Java libraries without framework
dependencies, so the verifier and other organizations use exactly the code the server uses. The server is one Spring
Boot application on PostgreSQL. The web UI in `frontend/` is a separate single-page application that talks to the
server's API.

</p>

| Module                                                            | Responsibility                                                                      |
|-------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| [`ledger/chain`](../ledger/chain/README.md)                       | Evidence format, hash chain, Merkle log, signed notes, COSE statements and receipts |
| [`ledger/mandate`](../ledger/mandate/README.md)                   | SD-JWT VC mandates, status lists, JWK keys and the mandate verifier SDK             |
| [`ledger/server`](../ledger/server/README.md)                     | Evidence API, gateways, grants, consent, checkpoints, witnesses and verification    |
| [`ledger/verifier`](../ledger/verifier/README.md)                 | Offline verifier for packages, statements and mandates, as a library and a jar      |
| [`ledger/demo-mcp`](../ledger/demo-mcp/README.md)                 | Demo MCP server and the demo script                                                 |
| [`ledger/ledger-e2e-tests`](../ledger/ledger-e2e-tests/README.md) | End-to-end tests against the server and PostgreSQL                                  |
| [`ledger/conformance`](../ledger/conformance/README.md)           | Conformance vectors for every format                                                |
| [`ledger/sdk/python`](../ledger/sdk/python/README.md)             | Python client and offline verifier, on the same conformance vectors                 |
| [`ledger/sdk/typescript`](../ledger/sdk/typescript/README.md)     | TypeScript client and offline verifier for Node and browsers                        |
| [`ledger/otel-exporter`](../ledger/otel-exporter/README.md)       | OpenTelemetry Collector exporter from agent spans to evidence                       |
| [`ledger/adapters`](../ledger/adapters/README.md)                 | Adapters for LangGraph, OpenAI Agents SDK, AgentCore and agentgateway               |
| [`ledger/verifier-web`](../ledger/verifier-web/README.md)         | Package verifier in the browser, as one HTML file with no network access            |
| [`frontend`](../frontend/README.md)                               | Web UI for operators and principals                                                 |

### Stack

* Java 21, Spring Boot 4.1, Maven
* PostgreSQL 18 with the `ledger` schema and Flyway migrations
* Spring JDBC, Spring Security OAuth2 JOSE for principal tokens, Micrometer with Prometheus
* HashiCorp Vault transit as an optional home for the signing key
* React and TypeScript for the web UI and the browser verifier
* Python and TypeScript SDKs that implement every format independently
* Go for the OpenTelemetry Collector exporter
* JUnit 5, AssertJ and Testcontainers for tests; Vitest for the web UI

### Server Packages

```text
com.nexusphere.ledger
├── evidence      evidence entries, the hash chain, checkpoints and packages
├── transparency  Merkle log, log checkpoints, proofs, witnesses, SCITT statements and receipts
├── agent         agents and their API keys
├── authorization grants, consent, decisions and mandates
├── mcp           MCP gateway
├── a2a           A2A gateway, inbound verification and receipts
└── server        configuration, security, signing keys, rate limits and errors
```

## Standards

| Standard                            | Used for                                         |
|-------------------------------------|--------------------------------------------------|
| RFC 9162                            | Merkle tree, inclusion and consistency proofs    |
| C2SP signed-note, tlog-checkpoint   | Log checkpoints                                  |
| C2SP tlog-witness, tlog-cosignature | Witness cosigning                                |
| IETF SCITT, RFC 9052 COSE           | Signed statements                                |
| RFC 9942                            | Receipts for signed statements                   |
| IETF SD-JWT VC                      | Mandates with selective disclosure               |
| IETF Token Status List              | Mandate revocation                               |
| RFC 8037, RFC 8032                  | Ed25519 keys and signatures                      |
| OpenID Connect, OAuth 2.0 PKCE      | Principal sign-in                                |
| MCP, A2A                            | Gateways for tool calls and agent-to-agent calls |
| OpenTelemetry GenAI conventions     | Evidence from agent spans                        |
| AP2, Verifiable Intent              | Mapping of mandates to payment intents           |

## Out of Scope

* Running agents or hosting models; the ledger only records and decides their actions
* Replacing agent platforms and gateways; the ledger sits beside them
* A policy language; grants are explicit lists of actions and targets
* Payments; AP2 and other payment protocols carry the money, the ledger keeps the proof
* Certification for a regulation; the ledger provides evidence that audits can use

##

**<p align="center">[Top](#nexusphere-project-description)</p>**
