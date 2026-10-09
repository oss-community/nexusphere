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
* Verify a mandate with the `mandate` command: issuer, key, signature, time, audience, coverage and revocation
* Pin the ledger's public key with `--public-key`, print JSON with `--json`, and exit with 0 (valid), 1 (invalid) or 2
  (usage error)
* Accept checkpoints signed by other keys of the ledger only when signed key rotations lead to them from the pinned key
* Run the [Conformance Vectors](../conformance/README.md) in `ConformanceVectorsTest`

## Dependencies

| Depends on         | Used by  |
|--------------------|----------|
| `chain`, `mandate` | `server` |

Usage is in [Evidence Packages](../README.md#evidence-packages), [SCITT Statements and
Receipts](../README.md#scitt-statements-and-receipts) and [Verifying a Mandate](../README.md#verifying-a-mandate).

##

**<p align="center">[Top](#nexusphere-ledger-verifier)</p>**
