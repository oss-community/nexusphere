# <p align="center">Nexusphere Ledger Conformance Vectors</p>

<p align="center">Fixed inputs and outputs for every format the ledger produces.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Keys](#keys)
* [Vectors](#vectors)
* [Checked Elsewhere](#checked-elsewhere)
* [Regenerate](#regenerate)

## Purpose

<p style="text-align: justify;">

A verifier written in another language, or another ledger, can prove it reads and writes the same formats by running
these vectors. The ledger runs them too: `ConformanceVectorsTest` in `ledger/verifier` rebuilds every vector and fails
when its output differs, so a format never changes by accident.

</p>

## Keys

<p style="text-align: justify;">

The vectors are signed with the published RFC 8032 Ed25519 test keys: test 1 is the ledger key and test 2 the witness
key. Ed25519 signatures are deterministic, so the same input always gives the same bytes. The keys are public; never
use them for real evidence.

</p>

## Vectors

| File                  | Contents                                                                                                    |
|-----------------------|-------------------------------------------------------------------------------------------------------------|
| `keys.json`           | Seeds, PKCS#8 and X.509 keys, the key ID and the C2SP verifier keys of the log and the witness             |
| `canonical-json.json` | An object and its canonical JSON with sorted keys, no whitespace and escaped text, and its SHA-256          |
| `evidence-chain.json` | Three entries with canonical content, content hash and link hash, and a signed checkpoint of the head       |
| `merkle-tree.json`    | RFC 9162 roots, inclusion proofs and consistency proofs for every size up to 8                             |
| `signed-note.json`    | The C2SP signed-note log checkpoint over the three entries and its `tlog-cosignature/v1` cosignature       |
| `scitt.json`          | COSE_Sign1 SCITT statements and RFC 9942 receipts for the three entries, in hex                            |
| `mandate.json`        | An SD-JWT VC mandate, the same mandate presented with only the grant disclosed, and its disclosed claims   |

<p style="text-align: justify;">

SD-JWT disclosures carry random salts, so `mandate.json` is checked rather than rebuilt: its signature, digests and
claims must verify.

</p>

## Checked Elsewhere

<p style="text-align: justify;">

The vectors were also checked with other implementations: the Merkle roots, canonical JSON, content hashes and
checkpoint signature with Python's `hashlib` and `cryptography`; the signed note with Go's
`golang.org/x/mod/sumdb/note`; the cosignature, COSE signatures and SD-JWT digests with Python's `cryptography` and
`cbor2`. The [Python SDK](../sdk/python/README.md) and the [TypeScript SDK](../sdk/typescript/README.md) rebuild every
vector except the mandate in their own test suites and check the mandate.

</p>

## Regenerate

<p style="text-align: justify;">

Change a vector only together with a deliberate format change, and record it in the changelog.

</p>

```shell
mvn -pl ledger/verifier -am test -Dtest=ConformanceVectorsTest -Dconformance.write=true -Dsurefire.failIfNoSpecifiedTests=false
```

##

**<p align="center">[Top](#nexusphere-ledger-conformance-vectors)</p>**
