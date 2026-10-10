# <p align="center">Nexusphere Ledger Python SDK</p>

<p align="center">Record evidence from a Python agent and verify everything the ledger produces, offline.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Install](#install)
* [Record Evidence](#record-evidence)
* [Decide and Report](#decide-and-report)
* [Record the Model and Prompt](#record-the-model-and-prompt)
* [Verify a Package](#verify-a-package)
* [Verify a Statement and Receipt](#verify-a-statement-and-receipt)
* [Prove That Something Never Happened](#prove-that-something-never-happened)
* [Verify a Mandate](#verify-a-mandate)
* [Present a Key-Bound Mandate](#present-a-key-bound-mandate)
* [Check a Signed Request](#check-a-signed-request)
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

## Record the Model and Prompt

<p style="text-align: justify;">

An entry can name the [model and prompt](../../README.md#model-and-prompt) the agent acted with: `model` is a dict with
any of `name`, `version`, `promptHash` and `configHash`, the two hashes being lowercase hex SHA-256. The model is part
of the content hash of the entry. The prompt text itself stays out of the chain; the ledger keeps it apart, and a
package carries it only when asked for.

</p>

Step 1. Store the system prompt; the answer is its hash, the same for the same text:

```python
prompt_hash = agent.store_prompt("You read the invoices of Acme. Ask before paying anything.")
```

Step 2. Record evidence and act under a grant with the model:

```python
model = {"name": "claude-sonnet-5-5", "version": "2026-09-01", "promptHash": prompt_hash}
agent.record("invoice-agent", "acme", "tools/call", "SUCCEEDED", target="read_invoice", model=model)
with agent.act("alice", "tools/call", "read_invoice", model=model) as action:
    action.output("invoice text")
```

Step 3. Read the prompt back, as the operator; it must print the stored text:

```python
print(operator.prompt(prompt_hash)["text"])
```

Step 4. Export a package with the prompt texts and verify it; `disclosed_prompts` must be `1`:

```python
from nexusphere_ledger import verify_package

package = operator.export_package(agent_id="invoice-agent", include_prompts=True)
report = verify_package(package, operator.active_public_key())
print(report.valid, report.disclosed_prompts)
```

<p style="text-align: justify;">

`decide` and `record_batch` items take `model` too, and `create_report` and `create_dispute` take
`include_prompts=True`. The verifier checks that each prompt text in a package hashes to its hash and that a
disclosed entry names that hash as its `promptHash` or `configHash`.

</p>

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
the pinned key. Pass `key_list` with the list from `GET /api/v1/keys` to apply key revocations, as the
[Ledger README](../../README.md#key-revocation) describes; the command takes it as `--keys keys.json`.
`report.to_dict()` has the same fields as the Java `PackageReport`. `report.compliance_profiles` lists the id and
digest of each [compliance profile](../../README.md#compliance-profiles) the checkpoint signs, and
`client.compliance()` returns the active profiles and their combined rules from the ledger. An entry whose personal
fields were [erased](../../README.md#erasure) still verifies from its commitments, and
`client.erase_principal(principal_id, reason)` asks the ledger to erase a principal. `place_legal_hold`,
`release_legal_hold`, `legal_holds` and `sweep_retention` manage [legal holds](../../README.md#retention-and-legal-holds)
and the retention pass. `timestamp_certificates`, a list of certificates or PEM text, pins the authority of the
[checkpoint timestamp](../../README.md#checkpoint-timestamps), `require_timestamp=True` refuses a checkpoint without
one, and `report.timestamped_at` and `report.timestamp_authority` say when and by whom it was timestamped.
`rekor_keys`, a list of verifier keys, pins the log of the [Rekor entry](../../README.md#rekor-anchoring),
`require_rekor=True` refuses a checkpoint without one, and `report.rekor_log_index` and `report.rekor_origin` say
where it was anchored. `report.disclosed_prompts` counts the prompt texts that match the hashes their entries name.

</p>

<p style="text-align: justify;">

`client.reports()` lists the [reports](../../README.md#reports-and-dispute-packages) the ledger offers,
`client.create_report(report_id, agent_id=...)` returns one signed by the ledger, or its HTML with `html=True`, and
`client.create_dispute(title, description, agent_id=...)` returns the bytes of a dispute package zip.
`verify_report(report, package, public_key)` checks the signature of a report with the keys the package is verified
with, and that it was made for the checkpoint, scope and disclosed entries of that package; `check.valid`,
`check.statuses` and `check.problems` give the result.

</p>

## Verify a Statement and Receipt

```python
from nexusphere_ledger import EvidenceStatement, LogReceipt, PublicKey

key = PublicKey.from_base64(public_key)
statement = EvidenceStatement.parse(agent.statement(entry["id"]))
receipt = LogReceipt.parse(agent.receipt(entry["id"]))
print(statement.verify(key), receipt.verify(statement.leaf_hash, key))
```

## Prove That Something Never Happened

<p style="text-align: justify;">

An [index proof](../../README.md#index-proofs) shows how often a term occurs in the evidence up to a checkpoint, or
that it never occurs. A term is an `agent` by `agentId`, an `action` by `agentId` and `action`, an `input` by
`inputHash`, or a `grant` by `delegationId`. `verify_index_proof` follows the path of the proof to the root of the index
and checks the ledger's signature on that root with the keys the proof carries, trusted from the pinned key as in a
package. Pass `key_list` to apply key revocations and `package` to check that the index belongs to the checkpoint of
that package.

</p>

Step 1. Record an action with a known input and sign a checkpoint, as in [Record Evidence](#record-evidence):

```python
from nexusphere_ledger import hash_of

input_hash = hash_of({"invoice": 7})
agent.record("invoice-agent", "acme", "tools/call", "SUCCEEDED", target="pay_invoice", input_hash=input_hash)
checkpoint = operator.create_checkpoint()
```

Step 2. Ask, as the operator, for a proof that the input was recorded and for a proof that the agent never deleted
anything; `sequence` picks the checkpoint and defaults to the latest:

```python
recorded = operator.index_proof({"type": "input", "inputHash": input_hash}, checkpoint["sequence"])
never = operator.index_proof({"type": "action", "agentId": "invoice-agent", "action": "files/delete"})
```

Step 3. Verify both pinned to the ledger's key; it must print `True True` with a count of at least 1, then
`True False`:

```python
from nexusphere_ledger import verify_index_proof

public_key = operator.active_public_key()
present = verify_index_proof(recorded, public_key)
absent = verify_index_proof(never, public_key)
print(present.valid, present.present, present.count, present.first_sequence, present.last_sequence)
print(absent.valid, absent.present, absent.sequence, absent.problems)
```

## Verify a Mandate

<p style="text-align: justify;">

`MandateVerifier` reads the issuer's keys from `/public/v1/keys` and its revocation status list, and caches both for
five minutes. Problems carry the same codes as the Java SDK: `MALFORMED`, `WRONG_TYPE`, `UNTRUSTED_ISSUER`,
`UNKNOWN_KEY`, `BAD_SIGNATURE`, `NOT_YET_VALID`, `EXPIRED`, `WRONG_AUDIENCE`, `NOT_COVERED`, `REVOKED`,
`STATUS_UNAVAILABLE`, `KEY_BINDING_MISSING`, `KEY_BINDING_INVALID` and `KEY_NOT_BOUND`.

</p>

```python
from nexusphere_ledger import MandateVerifier

verifier = MandateVerifier("http://localhost:8090", audience="https://supplier.example")
check = verifier.verify_action(token, "a2a/send", "supplier/sales")
print(check.valid, check.claims.agent_id, [p.code for p in check.problems])
```

## Present a Key-Bound Mandate

<p style="text-align: justify;">

An agent with its own key registers the public half with `set_signing_key`; mandates issued afterwards name it in
`cnf`. `present_bound` adds a KB-JWT signed by the agent for an audience and a nonce, and `verify_bound` checks it.
For the A2A gateway the nonce is the hex SHA-256 of the request body.

</p>

```python
from nexusphere_ledger import MandateVerifier, PrivateKey, hash_of, present_bound

key = PrivateKey.generate()
agent.set_signing_key("invoice-agent", key.public_key)
token = agent.issue_mandate(grant_id, "https://supplier.example")["token"]
presented = present_bound(token, key, "https://supplier.example", hash_of(body))
check = MandateVerifier("http://localhost:8090", audience="https://supplier.example").verify_bound(presented, hash_of(body))
print(check.valid, check.claims.holder_key.key_id)
```

## Check a Signed Request

<p style="text-align: justify;">

Every request the ledger forwards to an MCP server or an A2A agent carries an RFC 9421 HTTP message signature.
`verify_request` checks it with the ledger's published keys and returns the key ID, `created` and `nonce`; it raises
`InvalidHttpSignature` when the signature is missing, stale or does not match the method, address or body.
`sign_request` makes the same headers.

</p>

```python
from nexusphere_ledger import JwksKeys, verify_request
from nexusphere_ledger.mandate import http_fetcher

keys = JwksKeys(http_fetcher())
verified = verify_request("POST", "http://files-mcp:3000/mcp", request_headers, body,
                          lambda kid: keys.resolve("http://localhost:8090", kid))
print(verified.key_id, verified.nonce)
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

Step 4. Verify a key-bound presentation against its nonce; the output must show `Agent key` and end with
`Result: VALID`:

```shell
nexusphere-ledger-verify mandate --issuer http://localhost:8090 --audience https://supplier.example --nonce "{nonce}" "{presentation}"
```

Step 5. Verify a package from a ledger with [checkpoint timestamps](../../README.md#checkpoint-timestamps), pinned to
the authority's chain; the output must show `Timestamp   : ... (pinned)` and `Result: VALID`:

```shell
nexusphere-ledger-verify --timestamp-certificates tsa-chain.pem --require-timestamp package.json
```

Step 6. Verify a package from a ledger with [Rekor anchoring](../../README.md#rekor-anchoring), pinned to the log's
verifier key; the output must show `Rekor       : entry ... (pinned)` and `Result: VALID`:

```shell
nexusphere-ledger-verify --rekor-key "$REKOR_KEY" --require-rekor package.json
```

Step 7. Verify a [dispute package](../../README.md#reports-and-dispute-packages) with its signed report; the output
must show `Nexusphere Ledger report` and `Result: VALID`:

```shell
nexusphere-ledger-verify dispute --public-key "$LEDGER_PUBLIC_KEY" dispute.zip
```

Step 8. Verify the package of [Record the Model and Prompt](#record-the-model-and-prompt), saved as `package.json`;
the output must show `Prompts     : 1 prompt texts match the hashes their entries name` and `Result: VALID`:

```shell
nexusphere-ledger-verify --public-key "$LEDGER_PUBLIC_KEY" package.json
```

Step 9. Save the proof of absence from [Prove That Something Never Happened](#prove-that-something-never-happened)
with `json.dump(never, open("proof.json", "w"))` and verify it; the output must show
`Finding     : no entry up to sequence` and `Result: VALID`:

```shell
nexusphere-ledger-verify index --public-key "$LEDGER_PUBLIC_KEY" proof.json
```

## Reference

| Module      | Contents                                                                                         |
|-------------|--------------------------------------------------------------------------------------------------|
| `client`    | `LedgerClient` for evidence, batches, decisions, `act`, prompts, grants, mandates, packages, keys |
| `package`   | `verify_package` and `PackageReport`                                                             |
| `timestamp` | RFC 3161 `TimestampToken`, its verification against a pinned chain, and `read_certificates`      |
| `rekor`     | Rekor v2 `hashedrekord` requests and `verify_rekor_entry` against a pinned log                   |
| `report`    | Signed reports: `verify_report`, `ReportCheck`, `report_signed_bytes` and `entries_digest`       |
| `index`     | Index proofs of presence and absence: `verify_index_proof`, `IndexCheck`, `index_signed_bytes`   |
| `evidence`  | Content and link hashes, commitments, `ChainVerifier`, `Checkpoint` with its compliance profiles |
| `merkle`    | RFC 9162 roots, inclusion and consistency proofs                                                 |
| `note`      | C2SP signed notes, note keys and `tlog-cosignature/v1` cosignatures                              |
| `cose`      | `CoseSign1`, SCITT `EvidenceStatement` and RFC 9942 `LogReceipt`                                 |
| `mandate`   | SD-JWT VC mandates, `MandateVerifier`, status lists and JWKs                                     |
| `httpsig`   | RFC 9421 HTTP message signatures: `sign_request`, `verify_request`, `content_digest`             |
| `keys`      | Ed25519 keys, key IDs, key rotations and trusted keys from a pinned key                          |
| `canonical` | Canonical JSON                                                                                   |
| `cbor`      | Deterministic CBOR                                                                               |

## Test

Step 1. Run the unit tests and the conformance vectors; every test must pass:

```shell
cd ledger/sdk/python
PYTHONPATH=src python3 -m unittest discover -s tests -t .
```

Step 2. Run the same tests against a running ledger, as in the [Quick Start](../../../README.md#quick-start); the
seven `LiveLedger` tests must pass instead of being skipped:

```shell
NEXUSPHERE_LEDGER_URL=http://localhost:8090 PYTHONPATH=src python3 -m unittest discover -s tests -t . -v
```

##

**<p align="center">[Top](#nexusphere-ledger-python-sdk)</p>**
