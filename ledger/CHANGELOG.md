# <p align="center">Nexusphere Ledger Changelog</p>

<p align="center">What each release of the Nexusphere Ledger adds and changes.</p>

## <p align="center">Table of Content</p>

* [1.0.0 (not released yet)](#100-not-released-yet)

## 1.0.0 (not released yet)

<p style="text-align: justify;">

First release of the Nexusphere Ledger, a self-hosted evidence layer for AI agent actions that sits beside existing
agent platforms and gateways.

</p>

* Evidence entries in a SHA-256 hash chain with append-only PostgreSQL storage and Ed25519-signed checkpoints
* Evidence batches: up to 500 entries recorded in order in one call, all or none
* Transparency log: every entry is a leaf of an RFC 9162 Merkle tree, log checkpoints are C2SP signed notes, and
  inclusion and consistency proofs are served by the API and carried in evidence packages
* Witness cosigning with the C2SP `tlog-witness` protocol: the ledger asks configured witnesses to cosign each log
  checkpoint, any ledger can witness other logs, and the verifier can require cosignatures from given witnesses
* Log archives: the transparency log is served as C2SP `tlog-tiles` with immutable caching, the verifier's
  `archive` command mirrors it incrementally and its `tiles` command checks the copy and a package against it offline
* IETF SCITT signed statements (COSE hash envelopes) for every entry and RFC 9942 receipts from the transparency
  log, served by the API, carried in evidence packages and checked by the verifier's `statement` command
* Evidence packages with selective disclosure and the offline verifier command-line tool
* Conformance vectors for every format, signed with the RFC 8032 test keys and checked by a test
* Auditor Portal: one self-contained HTML page in React and TypeScript that verifies a package with the TypeScript
  SDK, pinned to the ledger key and optional witnesses, with a Content Security Policy that blocks every connection,
  and an investigation workspace with filtered entries and per-entry checks (hashes, link, inclusion proof,
  statement and receipt), the chain, the log signatures, signed reports, dispute ZIP archives and an audit
  worksheet in CSV
* Signing key rotation at startup with a rotation record signed by the old and the new key; retired keys stay
  published, so everything they signed still verifies, and the offline verifier follows rotations from a pinned key
* Signing providers: the ledger signs through a local key or a HashiCorp Vault transit key that never leaves Vault;
  a Vault key rotation becomes an endorsed ledger rotation, and a move from a local key to Vault is endorsed too
* Key revocation with a compromise time, signed by the active key and recorded as evidence; the verifiers in Java,
  Python, TypeScript and the browser accept a revoked key only where witness cosignatures prove the log checkpoint
  came before the compromise, and a new `key-history.json` conformance vector covers rotation and revocation
* RFC 3161 checkpoint timestamps: with `LEDGER_TIMESTAMP_URL` set, a timestamp authority timestamps every signed
  checkpoint, later when it is down; packages carry the token, the verifiers in Java, Python, TypeScript and the
  browser check it offline against a pinned chain with `--timestamp-certificates` and `--require-timestamp`, the
  profiles' `timestampAuthorities` decide which authority is accepted, and a `timestamp.json` conformance vector holds
  a token from a Sigstore timestamp authority
* Rekor anchoring: with `LEDGER_REKOR_URL` and `LEDGER_REKOR_KEY` set, the ledger writes every signed checkpoint to a
  Rekor v2 log as a `hashedrekord` entry signed with Ed25519ph, later when the log is down, and publishes the log at
  `GET /public/v1/rekor`; packages carry the entry, the verifiers in Java, Python, TypeScript and the browser check its
  inclusion proof offline against a pinned log with `--rekor-key` and `--require-rekor`, the web UI shows the entry,
  and a `rekor.json` conformance vector holds an entry from a real Rekor v2 log
* Index proofs: a sparse Merkle tree over agents, actions, inputs and the actions each grant allowed, with a root the
  ledger signs per checkpoint; `POST /api/v1/index/proofs` proves that a term occurs, with its count, or that it never
  did up to a checkpoint, the verifiers in Java, Python and TypeScript check proofs with `index`, and an
  `index-proof.json` conformance vector holds proofs of presence and absence
* Agents with their own API keys, grants from principals to agents, and ALLOW/DENY decisions with reason codes and
  outcome reports, all recorded as evidence
* Cedar policies: the operator activates a policy set that every decision must also pass, with a default effect of
  permit or deny; a denial is `POLICY_DENIED`, each decision records the policy set hash and the deciding policies,
  and packages carry the policy texts that the verifier and SDKs check against their hashes
* Human approval: a forbid policy annotated `@approval` turns its denial into a request that the principal or the
  operator answers in the ledger, the principal also on their device through OpenID Connect CIBA, announced by a
  signed webhook; an approved request is allowed once, and a request nobody answers in time is denied
* Principal login with any OpenID Connect provider: principals approve, deny, create and revoke their own grants,
  grants from the operator or an agent wait for that consent, and every consent is evidence tied to the sign-in
* Anomaly alerts: each agent's volume, denial share and failure share are compared with its own baseline, new
  anomalies are recorded as evidence and exported as `ledger_anomaly_score`, and Alertmanager delivers
  `LedgerAgentAnomaly`
* MCP gateway that decides every `tools/call`, forwards only allowed calls and records their outcome
* Signed mandates issued from grants as IETF SD-JWT VCs (`dc+sd-jwt`) signed with EdDSA: the principal, grant and
  terms hash are selectively disclosable, the limits are not, and the documentation maps them to AP2 and Verifiable
  Intent; a signed revocation status list and the ledger keys as a JWK set on public endpoints, with issue and revoke
  recorded as evidence
* `MandateVerifier` SDK that checks a mandate's issuer, key, signature, time, audience, coverage and revocation, and
  the `mandate` command of the verifier jar
* Agent signing keys: an agent registers its own Ed25519 key, mandates name it in `cnf`, and the agent presents
  them with an SD-JWT key binding (KB-JWT) for the audience and the request body hash; the A2A gateway and the
  receiving ledger refuse a bound mandate without a valid key binding, and the verifiers in Java, Python and
  TypeScript check it, with a `mandate-key-binding.json` conformance vector
* A2A gateway between two ledgers: outbound requests are decided and carry a mandate and a signed request proof,
  inbound requests are verified, recorded and answered with a signed receipt, so both sides hold matching evidence
* Streaming in both gateways: MCP answers, server requests and the `GET` stream, and A2A `message/stream` and
  `tasks/resubscribe` are relayed live, with the A2A receipt sent as the last event and covering every event
* Grant uses are returned when a gateway sees that the call never ran, and a receiving ledger counts the uses of
  each mandate on its own
* RFC 9421 HTTP message signatures with an RFC 9530 `Content-Digest` on every request the ledger sends to MCP
  servers, peer ledgers and A2A agents; the receiving ledger requires them, and the Java, Python and TypeScript SDKs
  sign and verify them, with an `http-signature.json` conformance vector
* A2A reconciliation: each ledger signs a statement of its exchanges with a peer over a window, and
  `POST /api/v1/a2a/reconciliations` compares the peer's statement with its own request by request, reporting
  requests one side never received, never sent or recorded differently, and records the result as evidence; the
  `reconcile` command and the Python and TypeScript SDKs compare two statements offline, and an
  `a2a-reconciliation.json` conformance vector holds a pair
* Agent track records: `POST /api/v1/agents/{agentId}/track-record` issues an SD-JWT VC that counts the agent's
  evidence up to a checkpoint, with outcomes, principals and actions the agent may withhold, bound to the agent's key
  and tied to the signed index root; `TrackRecordVerifier`, the verifiers in Python and TypeScript and the
  `track-record` command check it and cross-check it with an index proof, and an `agent-track-record.json`
  conformance vector holds one
* Compliance profiles: retention, erasure, region, required evidence fields, accepted timestamp authorities and
  report templates per jurisdiction in JSON files, with `baseline`, `eu` and `us` built in and your own added from a
  directory; the strictest rule of the active profiles wins, required fields are enforced, a disallowed region stops
  the ledger, checkpoints sign the active profiles in format `checkpoint/v2`, and the verifiers report them, with a
  `compliance-checkpoint.json` conformance vector
* Evidence format v2 with crypto-shredding: the hash covers salted commitments of the personal fields instead of
  their values, which the ledger keeps encrypted under a key per principal; `POST /api/v1/principals/{id}/erasure`
  deletes them past the retention of the active profiles and pseudonymizes the principal in grants, decisions,
  mandates and exchanges, while the chain, log, checkpoints and packages stay valid; format v2 replaces v1, so a
  ledger database with v1 evidence must be recreated
* Retention and legal holds: a profile's retention rule can set a `maximum`, the ledger erases personal data past the
  shortest maximum on a schedule and completes pending erasures once their retention ends, and a legal hold on a
  principal stops both until it is released; holds, expiries and erasures are recorded as evidence, and the web UI
  has a Privacy page for them
* Reports and dispute packages: each compliance profile's report templates (`dispute`, `eu-ai-act-logging`,
  `dora-ict-records`, `finra-supervision`, `sec-17a-4-records`) map the evidence of a package to requirements as a
  signed `nexusphere-ledger/report/v1` report in JSON or HTML, `POST /api/v1/disputes` answers a ZIP with the package,
  the dispute report, keys and profiles, the verifiers in Java, Python and TypeScript check reports and dispute
  packages with `report` and `dispute`, the web UI has a Reports page, and a `report.json` conformance vector holds a
  signed report
* Model and prompt in evidence: an entry names the model, version, prompt hash and config hash the agent ran,
  covered by the content hash, sent through the gateways as `X-Ledger-Model*` headers and through the OpenTelemetry
  exporter, and required by a profile when it lists them; `PUT /api/v1/prompts` stores prompt texts by hash, packages,
  reports and disputes carry them with `includePrompts`, every verifier checks them, the EU AI Act and dispute
  reports have a model section, and an `evidence-model.json` conformance vector holds an entry with its model
* Web UI (React and TypeScript) for operators and principals: evidence, agents, grants, mandates, packages, A2A
  exchanges, ledger verification, and principal sign-in with PKCE to approve, deny and revoke grants
* Principal dashboard: the activity of the agents acting for a principal per day and per agent, the approvals waiting
  for them and an export of a package of their own evidence, with browser tests
* Questions in plain language: `POST /api/v1/ask` and `POST /api/v1/principal/ask` answer from the evidence with a
  built-in rule-based model or any OpenAI-compatible model you run, such as Ollama, llama.cpp or vLLM; every entry is
  checked by its hash, link and inclusion proof before the model sees it, sentences without valid citations are
  dropped, a principal hears only about their own evidence, each question is recorded as evidence, and the web UI has
  an Ask page
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
* High availability: several instances share one PostgreSQL and one chain, and the scheduled work runs only on
  the leader, the instance holding a PostgreSQL advisory lock, which another instance takes over when it stops
* The published development API key and signing key are refused outside the `dev` profile before the database is
  opened, so a refused start leaves no key behind
* Secrets and signing keys from files written by a secret manager, Prometheus metrics with alert rules, and a tested
  backup and restore procedure
* Rate limit per caller with 429 and `Retry-After`, and alert delivery by email, Slack, Telegram or webhook, each
  turned on by setting its address
* Published benchmarks: load scenarios for recording, decisions, the MCP gateway and package export, with the results
  of a reference machine
* Dockerfiles, compose file with Adminer, Keycloak, Prometheus, Alertmanager and the web UI, Kubernetes manifest, demo
  MCP server and demo script

##

**<p align="center">[Top](#nexusphere-ledger-changelog)</p>**
