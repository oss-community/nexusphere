# <p align="center">Nexusphere Ledger Python SDK</p>

<p align="center">Record evidence from a Python agent and verify everything the ledger produces, offline.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Install](#install)
* [Record Evidence](#record-evidence)
* [Decide and Report](#decide-and-report)
* [Verify a Package](#verify-a-package)
* [Verify a Statement and Receipt](#verify-a-statement-and-receipt)
* [Verify a Mandate](#verify-a-mandate)
* [Command Line](#command-line)
* [Reference](#reference)
* [Test](#test)

## Purpose

<p style="text-align: justify;">

`nexusphere-ledger` lets a Python agent, a gateway hook or a platform record evidence in the Nexusphere Ledger, and
lets an auditor or a counterparty check what a ledger handed them without Java and without the server. The verifier is
a second implementation of the formats, not a wrapper: it passes every [Conformance Vector](../../conformance/README.md)
and rebuilds the signed checkpoints, notes, cosignatures, COSE statements and receipts byte for byte. Its only
dependency is `cryptography`.

</p>

## Install

Step 1. Check Python; the version must be 3.10 or later:

```shell
python3 --version
```

Step 2. Install the SDK from the repository:

```shell
python3 -m pip install ledger/sdk/python
```

Step 3. Check the install; it must print `1.0.0`:

```shell
python3 -c "import nexusphere_ledger; print(nexusphere_ledger.__version__)"
```

## Record Evidence

<p style="text-align: justify;">

These steps need a running ledger, as in the [Quick Start](../../../README.md#quick-start), and an agent API key from
`POST /api/v1/agents`. `input` and `output` are hashed on the client with SHA-256; only the hash reaches the ledger.

</p>

Step 1. Register an agent with the operator key and keep its API key:

```python
from nexusphere_ledger import LedgerClient

operator = LedgerClient("http://localhost:8090", "nexusphere-ledger-development-key-change-me")
agent_key = operator.register_agent("invoice-agent", "Invoice agent", "acme")["apiKey"]
```

Step 2. Record what the agent did; the answer carries the `sequence` and `hash` of the new entry:

```python
agent = LedgerClient("http://localhost:8090", agent_key)
entry = agent.record("invoice-agent", "acme", "tools/call", "SUCCEEDED", target="read_invoice",
                     decision="ALLOW", input={"invoice": 7}, output="invoice text")
print(entry["sequence"], entry["hash"])
```

Step 3. Record many entries in one call, all or none:

```python
agent.record_batch([
    {"agentId": "invoice-agent", "principalId": "acme", "action": "tools/call", "outcome": "SUCCEEDED"},
    {"agentId": "invoice-agent", "principalId": "acme", "action": "tools/call", "outcome": "FAILED"},
])
```

## Decide and Report

<p style="text-align: justify;">

When the ledger decides, `act` asks for a decision, raises `Denied` when no grant covers the action, and reports the
outcome when the block ends: `SUCCEEDED`, or `FAILED` with the exception name.

</p>

Step 1. Grant the agent the tools, as the operator:

```python
from datetime import datetime, timedelta, timezone

operator.create_grant("alice", "invoice-agent", ["tools/call"], ["read_*"],
                      datetime.now(timezone.utc) + timedelta(days=30), max_uses=100)
```

Step 2. Act under the grant:

```python
from nexusphere_ledger import Denied

try:
    with agent.act("alice", "tools/call", "read_invoice", input={"invoice": 7}) as action:
        text = "invoice text"
        action.output(text)
except Denied as denied:
    print(denied.decision["reasonCode"])
```

Step 3. Check the outcome; it must print `SUCCEEDED`:

```python
print(action.reported["outcome"])
```

## Verify a Package

Step 1. Export a package and read the ledger's public key, as the operator:

```python
package = operator.export_package(agent_id="invoice-agent")
public_key = operator.active_public_key()
```

Step 2. Verify it pinned to that key; `valid` must be `True`:

```python
from nexusphere_ledger import verify_package

report = verify_package(package, public_key)
print(report.valid, report.disclosed_entries, report.problems)
```

<p style="text-align: justify;">

Pass witness verifier keys as the third argument and the number of cosignatures needed as the fourth, as with the
Java verifier. A key in the package is trusted only when it is the pinned key or signed key rotations lead to it from
the pinned key. `report.to_dict()` has the same fields as the Java `PackageReport`.

</p>

## Verify a Statement and Receipt

```python
from nexusphere_ledger import EvidenceStatement, LogReceipt, PublicKey

key = PublicKey.from_base64(public_key)
statement = EvidenceStatement.parse(agent.statement(entry["id"]))
receipt = LogReceipt.parse(agent.receipt(entry["id"]))
print(statement.verify(key), receipt.verify(statement.leaf_hash, key))
```

## Verify a Mandate

<p style="text-align: justify;">

`MandateVerifier` reads the issuer's keys from `/public/v1/keys` and its revocation status list, and caches both for
five minutes. Problems carry the same codes as the Java SDK: `MALFORMED`, `WRONG_TYPE`, `UNTRUSTED_ISSUER`,
`UNKNOWN_KEY`, `BAD_SIGNATURE`, `NOT_YET_VALID`, `EXPIRED`, `WRONG_AUDIENCE`, `NOT_COVERED`, `REVOKED` and
`STATUS_UNAVAILABLE`.

</p>

```python
from nexusphere_ledger import MandateVerifier

verifier = MandateVerifier("http://localhost:8090", audience="https://supplier.example")
check = verifier.verify_action(token, "a2a/send", "supplier/sales")
print(check.valid, check.claims.agent_id, [p.code for p in check.problems])
```

## Command Line

<p style="text-align: justify;">

The install adds `nexusphere-ledger-verify` with the same arguments, output and exit codes (0 valid, 1 invalid, 2
usage error) as the Java verifier jar.

</p>

Step 1. Verify a package pinned to the ledger's key; the last line must be `Result: VALID`:

```shell
nexusphere-ledger-verify --public-key "$(curl -s http://localhost:8090/api/v1/keys -H 'Authorization: Bearer nexusphere-ledger-development-key-change-me' | jq -r '.[] | select(.status == "ACTIVE") | .publicKey')" package.json
```

Step 2. Verify a statement with its receipt:

```shell
nexusphere-ledger-verify statement --public-key "{publicKey}" --receipt receipt.cose statement.cose
```

Step 3. Verify a mandate for an action:

```shell
nexusphere-ledger-verify mandate --issuer http://localhost:8090 --action a2a/send --target supplier/sales "{token}"
```

## Reference

| Module      | Contents                                                                                        |
|-------------|-------------------------------------------------------------------------------------------------|
| `client`    | `LedgerClient` for evidence, batches, decisions, `act`, grants, mandates, packages, keys, proofs |
| `package`   | `verify_package` and `PackageReport`                                                            |
| `evidence`  | Content and link hashes, `ChainVerifier`, `Checkpoint`                                          |
| `merkle`    | RFC 9162 roots, inclusion and consistency proofs                                                |
| `note`      | C2SP signed notes, note keys and `tlog-cosignature/v1` cosignatures                             |
| `cose`      | `CoseSign1`, SCITT `EvidenceStatement` and RFC 9942 `LogReceipt`                                |
| `mandate`   | SD-JWT VC mandates, `MandateVerifier`, status lists and JWKs                                    |
| `keys`      | Ed25519 keys, key IDs, key rotations and trusted keys from a pinned key                         |
| `canonical` | Canonical JSON                                                                                  |
| `cbor`      | Deterministic CBOR                                                                              |

## Test

Step 1. Run the unit tests and the conformance vectors; every test must pass:

```shell
cd ledger/sdk/python
PYTHONPATH=src python3 -m unittest discover -s tests -t .
```

Step 2. Run the same tests against a running ledger, as in the [Quick Start](../../../README.md#quick-start); the
three `LiveLedger` tests must pass instead of being skipped:

```shell
NEXUSPHERE_LEDGER_URL=http://localhost:8090 PYTHONPATH=src python3 -m unittest discover -s tests -t . -v
```

##

**<p align="center">[Top](#nexusphere-ledger-python-sdk)</p>**
