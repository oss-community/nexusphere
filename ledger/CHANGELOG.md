# <p align="center">Nexusphere Ledger Changelog</p>

## 1.0.0 (not released yet)

First release of the Nexusphere Ledger, a self-hosted evidence layer for AI agent actions that sits beside existing
agent platforms and gateways.

* Evidence entries in a SHA-256 hash chain with append-only PostgreSQL storage and Ed25519-signed checkpoints
* Agents with their own API keys, grants from principals to agents, and ALLOW/DENY decisions with reason codes and
  outcome reports, all recorded as evidence
* MCP gateway that decides every `tools/call`, forwards only allowed calls and records their outcome
* Evidence packages with selective disclosure and the offline verifier command-line tool
* Signed mandates (EdDSA JWS) issued from grants, a signed revocation status list and the ledger keys as a JWK set
  on public endpoints, with issue and revoke recorded as evidence
* `MandateVerifier` SDK that checks a mandate's issuer, key, signature, time, audience, coverage and revocation, and
  the `mandate` command of the verifier jar
* A2A gateway between two ledgers: outbound requests are decided and carry a mandate and a signed request proof,
  inbound requests are verified, recorded and answered with a signed receipt, so both sides hold matching evidence
* Signing key rotation at startup with a rotation record signed by the old and the new key; retired keys stay
  published, so everything they signed still verifies, and the offline verifier follows rotations from a pinned key
* Grant uses are returned when a gateway sees that the call never ran, and a receiving ledger counts the uses of
  each mandate on its own
* Streaming in both gateways: MCP answers, server requests and the `GET` stream, and A2A `message/stream` and
  `tasks/resubscribe` are relayed live, with the A2A receipt sent as the last event and covering every event
* Principal login with any OpenID Connect provider: principals approve, deny, create and revoke their own grants,
  grants from the operator or an agent wait for that consent, and every consent is evidence tied to the sign-in
* Web UI (React and TypeScript) for operators and principals: evidence, agents, grants, mandates, packages, A2A
  exchanges, ledger verification, and principal sign-in with PKCE to approve, deny and revoke grants
* Evidence batches: up to 500 entries recorded in order in one call, all or none
* Python SDK (`nexusphere-ledger`): a client for evidence, decisions, grants, mandates and packages, and an offline
  verifier for packages, statements, receipts and mandates that passes every conformance vector, with the
  `nexusphere-ledger-verify` command
* TypeScript SDK (`@nexusphere/ledger`): the same client and offline verifier for Node 20 and browsers on Web
  Crypto, with no runtime dependencies and the same `nexusphere-ledger-verify` command
* OpenTelemetry Collector exporter `nexusphere`: agent tool call and agent invocation spans that follow the GenAI
  semantic conventions become evidence in batches, with retries and a sending queue, plus a ready Collector image;
  MCP spans with a JSON-RPC error are recorded as `FAILED`
* Adapters that record or decide tool calls: a LangGraph callback handler and `govern` wrapper, OpenAI Agents SDK run
  hooks with a tool input guardrail, an Amazon Bedrock AgentCore Gateway interceptor for Lambda, and an agentgateway
  tracing configuration with an end-to-end check
* Signing providers: the ledger signs through a local key or a HashiCorp Vault transit key that never leaves Vault;
  a Vault key rotation becomes an endorsed ledger rotation, and a move from a local key to Vault is endorsed too
* Browser verifier: one self-contained HTML page in React and TypeScript that verifies a package with the
  TypeScript SDK, pinned to the ledger key and optional witnesses, with a Content Security Policy that blocks every
  connection
* Secrets and signing keys from files written by a secret manager, Prometheus metrics with alert rules, and a tested
  backup and restore procedure
* Rate limit per caller with 429 and `Retry-After`, and alert delivery by email, Slack, Telegram or webhook, each
  turned on by setting its address
* Transparency log: every entry is a leaf of an RFC 9162 Merkle tree, log checkpoints are C2SP signed notes, and
  inclusion and consistency proofs are served by the API and carried in evidence packages
* Witness cosigning with the C2SP `tlog-witness` protocol: the ledger asks configured witnesses to cosign each log
  checkpoint, any ledger can witness other logs, and the verifier can require cosignatures from given witnesses
* IETF SCITT signed statements (COSE hash envelopes) for every entry and RFC 9942 receipts from the transparency
  log, served by the API, carried in evidence packages and checked by the verifier's `statement` command
* Mandates are IETF SD-JWT VCs (`dc+sd-jwt`): the principal, grant and terms hash are selectively disclosable, the
  limits are not, and the documentation maps them to AP2 and Verifiable Intent
* Conformance vectors for every format, signed with the RFC 8032 test keys and checked by a test
* Dockerfiles, compose file with Adminer, Keycloak, Prometheus, Alertmanager and the web UI, Kubernetes manifest, demo MCP server
  and demo script

##

**<p align="center">[Top](#nexusphere-ledger-changelog)</p>**
