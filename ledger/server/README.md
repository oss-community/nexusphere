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

* Evidence API on an append-only SHA-256 hash chain in PostgreSQL, with signed checkpoints and full verification
* Agents with their own API keys, grants from principals to agents, and ALLOW or DENY decisions with outcomes
* MCP gateway that decides and records every tool call
* Evidence packages with selective disclosure for auditors
* Signed mandates, the revocation status list and the public keys as JWKS
* A2A gateway that sends and receives requests between two ledgers with evidence and signed receipts on both sides

## Dependencies

| Depends on         | Used by |
|--------------------|---------|
| `chain`, `mandate` | none    |

## API

| Path                                 | Purpose                                                                            |
|--------------------------------------|------------------------------------------------------------------------------------|
| `/api/v1/**`                         | Evidence, checkpoints, packages, agents, grants, decisions, mandates and exchanges |
| `/mcp/{server}`                      | MCP gateway                                                                        |
| `/a2a/out/{peer}`, `/a2a/in/{agent}` | A2A gateway                                                                        |
| `/public/v1/**`                      | Public keys and the mandate status list                                            |

## Storage

Tables live in the `ledger` schema of PostgreSQL, created by the Flyway migrations in
`src/main/resources/db/migration/ledger`.

Configuration, every endpoint and the formats are described in [Nexusphere Ledger](../README.md).

##

**<p align="center">[Top](#nexusphere-ledger-server)</p>**
