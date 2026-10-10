# <p align="center">Nexusphere Ledger Verifier</p>

<p align="center">A command-line tool that checks ledger output without the ledger.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)

## Purpose

<p style="text-align: justify;">

Evidence is only worth something if someone else can check it. The verifier runs anywhere with Java and no server or
database, so an auditor, a court or a counterparty can verify what a ledger handed them.

</p>

## Responsibilities

* Verify an evidence package: checkpoint signatures, every hash link, and that each disclosed entry belongs to the chain
* Check the package's log checkpoint and the inclusion proof of every disclosed entry, and require witness
  cosignatures with `--witness` and `--witnesses-required`
* Check the SCITT statement and receipt of every disclosed entry, and a single statement and receipt with the
  `statement` command
* Verify a mandate with the `mandate` command: issuer, key, signature, time, audience, coverage and revocation, and
  the agent's key binding against `--nonce`, refusing unbound mandates with `--require-key-binding`
* Pin the ledger's public key with `--public-key`, print JSON with `--json`, and exit with 0 (valid), 1 (invalid) or 2
  (usage error)
* Accept checkpoints signed by other keys of the ledger only when signed key rotations lead to them from the pinned key
* Apply key revocations from the package or from the key list given with `--keys`: a revoked key counts only where
  witness cosignatures prove that the log checkpoint predates its compromise
* Report the compliance profiles each checkpoint signs, by id and digest
* Check the RFC 3161 timestamp of the checkpoint, pin the authority's chain with `--timestamp-certificates` and
  refuse a checkpoint without one with `--require-timestamp`
* Check the Rekor entry of the checkpoint and its inclusion proof, pin the log with `--rekor-key` and refuse a
  checkpoint without an entry with `--require-rekor`
* Check a signed report against its package with `report --package`, and a whole dispute package with `dispute`
* Check an index proof with `index`: the term, the path to the signed index root and, with `--package`, that the
  index belongs to the package's checkpoint
* Check entries in evidence format v2 from their personal values and salts, or from their commitments once erased
* Check that the prompt texts of a package match their hashes and are named by the model of a disclosed entry
* Run the [Conformance Vectors](../conformance/README.md) in `ConformanceVectorsTest`

## Dependencies

| Depends on         | Used by  |
|--------------------|----------|
| `chain`, `mandate` | `server` |

Usage is in [Evidence Packages](../README.md#evidence-packages), [SCITT Statements and
Receipts](../README.md#scitt-statements-and-receipts), [Verifying a Mandate](../README.md#verifying-a-mandate) and
[Reports and Dispute Packages](../README.md#reports-and-dispute-packages).

##

**<p align="center">[Top](#nexusphere-ledger-verifier)</p>**
