# <p align="center">Nexusphere Ledger Changelog</p>

## Unreleased

* Signed mandates (EdDSA JWS) issued from grants, a signed revocation status list and the ledger keys as a JWK set
  on public endpoints, with issue and revoke recorded as evidence (M1)
* `MandateVerifier` SDK that checks a mandate's issuer, key, signature, time, audience, coverage and revocation, and
  the `mandate` command of the verifier jar (M2)
* A2A gateway between two ledgers: outbound requests are decided and carry a mandate and a signed request proof,
  inbound requests are verified, recorded and answered with a signed receipt, so both sides hold matching evidence (M3)

## 0.1.0

First release of the Nexusphere Ledger, a self-hosted evidence ledger for AI agent actions.

* Evidence entries in a SHA-256 hash chain with append-only PostgreSQL storage and Ed25519-signed checkpoints (L1)
* Agents with their own API keys, grants from principals to agents, and ALLOW/DENY decisions with reason codes and
  outcome reports, all recorded as evidence (L2)
* MCP gateway that decides every `tools/call`, forwards only allowed calls and records their outcome (L3)
* Evidence packages with selective disclosure and the offline verifier command-line tool (L4)
* Dockerfiles, compose file, demo MCP server and demo script (L5)

##

**<p align="center">[Top](#nexusphere-ledger-changelog)</p>**
