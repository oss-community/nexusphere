# <p align="center">Nexusphere Ledger Chain</p>

<p align="center">The evidence format and its cryptography, with no framework.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)

## Purpose

<p style="text-align: justify;">

Anyone who checks the ledger, including an auditor or a counterparty without the server, needs the exact rules for
hashing and signing. This library is those rules in plain Java, shared by the server, the verifier and anyone who wants
to verify evidence on their own.

</p>

## Responsibilities

* Canonical JSON, so the same content always hashes to the same bytes
* `EvidenceEntry` with its content hash and link hash, and `EvidenceLink` for entries disclosed without their content
* `ChainVerifier`, which checks the hash chain from genesis or from an anchor
* Ed25519 keys and signatures (`SigningKeys`) and signed checkpoints (`Checkpoint`, `SignedCheckpoint`)
* Signed key rotations (`KeyRotation`) and the keys a pinned key reaches through them (`TrustedKeys`)
* `GrantTerms`, the hashed terms of a grant

## Dependencies

| Depends on | Used by                         |
|------------|---------------------------------|
| none       | `mandate`, `server`, `verifier` |

##

**<p align="center">[Top](#nexusphere-ledger-chain)</p>**
