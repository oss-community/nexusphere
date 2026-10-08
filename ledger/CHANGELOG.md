# <p align="center">Nexusphere Ledger Changelog</p>

## 1.0.0 (not released yet)

First release of the Nexusphere Ledger, a self-hosted evidence ledger for AI agent actions.

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
* Dockerfiles, compose file with Adminer and Keycloak, demo MCP server and demo script

##

**<p align="center">[Top](#nexusphere-ledger-changelog)</p>**
