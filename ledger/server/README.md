# <p align="center">Nexusphere Ledger Server</p>

<p align="center">The ledger service: evidence, authorization and gateways for agents.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

This is the product that runs: a self-hosted service that records every agent action as tamper-evident evidence, decides
whether an agent may act, and sits in front of MCP servers and A2A peers so nothing passes without a decision and a
record.

</p>

## Responsibilities

* Evidence API on an append-only SHA-256 hash chain in PostgreSQL, single or in batches, with signed checkpoints
  and full verification
* Agents with their own API keys, grants from principals to agents, and ALLOW or DENY decisions with outcomes
* Cedar policy sets that the operator activates and every decision must also pass, with the policy hash in evidence
* Human approval of decisions that policies hold back, by the operator, the principal or CIBA, with a webhook
* Anomaly detection per agent against its own baseline, recorded as evidence and alerted through Alertmanager
* Several instances on one database, with leader election by advisory lock for the scheduled work
* MCP gateway that decides and records every tool call
* Evidence packages with selective disclosure for auditors
* Transparency log over the evidence with inclusion and consistency proofs, cosigned by witnesses, served as C2SP
  `tlog-tiles` for archives, and a C2SP witness endpoint so the ledger can witness other logs
* SCITT signed statements and RFC 9942 receipts for every entry
* Signed mandates, the revocation status list and the public keys as JWKS
* Signing key history: rotation at startup with a cross-signed rotation record, retired keys kept for verification
* A2A gateway that sends and receives requests between two ledgers with evidence and signed receipts on both sides
* Principal sign-in with an OpenID Connect provider and principal consent on grants, their activity per day and
  agent, and a package of their own evidence
* Questions in plain language answered from verified evidence with citations, by a built-in or an
  OpenAI-compatible model
* Health probes and Prometheus metrics for the chain head, checkpoints and recorded evidence

## Dependencies

| Depends on                     | Used by            |
|--------------------------------|--------------------|
| `chain`, `mandate`, `verifier` | `ledger-e2e-tests` |

## API

| Path                                 | Purpose                                                                          |
|--------------------------------------|----------------------------------------------------------------------------------|
| `/api/v1/**`                         | Evidence, checkpoints, log proofs, packages, agents, grants, decisions, mandates |
| `/mcp/{server}`                      | MCP gateway                                                                      |
| `/a2a/out/{peer}`, `/a2a/in/{agent}` | A2A gateway                                                                      |
| `/public/v1/**`                      | Public keys, status list, OIDC settings, log checkpoint and witness endpoint     |

## Storage

<p style="text-align: justify;">

Tables live in the `ledger` schema of PostgreSQL, created by the Flyway migrations in
`src/main/resources/db/migration/ledger`. Configuration, every endpoint and the formats are described in
[Nexusphere Ledger](../README.md).

</p>

##

**<p align="center">[Top](#nexusphere-ledger-server)</p>**
