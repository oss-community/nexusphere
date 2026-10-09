# <p align="center">Nexusphere Ledger TypeScript SDK</p>

<p align="center">Record evidence from a Node or browser agent and verify everything the ledger produces, offline.</p>

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

`@nexusphere/ledger` is the TypeScript counterpart of the [Python SDK](../python/README.md): a client for agents,
gateways and platforms that record evidence, and an offline verifier for packages, SCITT statements, receipts and
mandates. It has no runtime dependencies. Signatures and hashes use Web Crypto and status lists use
`DecompressionStream`, so the same code runs in Node 20 or later and in current browsers. It passes every
[Conformance Vector](../../conformance/README.md) byte for byte. Every call that hashes or checks a signature returns
a promise.

</p>

## Install

Step 1. Check Node; the version must be 20 or later:

```shell
node --version
```

Step 2. Build the SDK and add it to your project:

```shell
cd ledger/sdk/typescript
npm install
npm run build
cd -
npm install ./ledger/sdk/typescript
```

Step 3. Check the install; it must print `1.0.0`:

```shell
node --input-type=module -e "import { VERSION } from '@nexusphere/ledger'; console.log(VERSION)"
```

## Record Evidence

<p style="text-align: justify;">

These steps need a running ledger, as in the [Quick Start](../../../README.md#quick-start). `input` and `output` are
hashed on the client with SHA-256; only the hash reaches the ledger.

</p>

Step 1. Register an agent with the operator key and keep its API key:

```typescript
import { LedgerClient } from "@nexusphere/ledger";

const operator = new LedgerClient("http://localhost:8090", { apiKey: "nexusphere-ledger-development-key-change-me" });
const { apiKey } = await operator.registerAgent("invoice-agent", "Invoice agent", "acme");
```

Step 2. Record what the agent did; the answer carries the `sequence` and `hash` of the new entry:

```typescript
const agent = new LedgerClient("http://localhost:8090", { apiKey: apiKey as string });
const entry = await agent.record({
  agentId: "invoice-agent", principalId: "acme", action: "tools/call", outcome: "SUCCEEDED",
  target: "read_invoice", decision: "ALLOW", input: { invoice: 7 }, output: "invoice text",
});
console.log(entry.sequence, entry.hash);
```

Step 3. Record many entries in one call, all or none:

```typescript
await agent.recordBatch([
  { agentId: "invoice-agent", principalId: "acme", action: "tools/call", outcome: "SUCCEEDED" },
  { agentId: "invoice-agent", principalId: "acme", action: "tools/call", outcome: "FAILED" },
]);
```

## Decide and Report

<p style="text-align: justify;">

`act` asks for a decision, throws `Denied` when no grant covers the action, runs the work, and reports `SUCCEEDED`, or
`FAILED` with the error name when the work throws.

</p>

Step 1. Grant the agent the tools, as the operator:

```typescript
await operator.createGrant({
  principalId: "alice", agentId: "invoice-agent", actions: ["tools/call"], targets: ["read_*"],
  expiresAt: new Date(Date.now() + 30 * 86_400_000), maxUses: 100,
});
```

Step 2. Act under the grant; it must print `SUCCEEDED`:

```typescript
import { Denied } from "@nexusphere/ledger";

try {
  await agent.act({ principalId: "alice", action: "tools/call", target: "read_invoice", input: { invoice: 7 } },
    async (action) => {
      await action.output("invoice text");
      console.log("running");
    });
} catch (e) {
  if (e instanceof Denied) console.log(e.decision.reasonCode);
}
```

## Verify a Package

Step 1. Export a package and read the ledger's public key, as the operator:

```typescript
const pkg = await operator.exportPackage({ agentId: "invoice-agent" });
const publicKey = await operator.activePublicKey();
```

Step 2. Verify it pinned to that key; `valid` must be `true`:

```typescript
import { verifyPackage } from "@nexusphere/ledger";

const report = await verifyPackage(pkg, publicKey);
console.log(report.valid, report.disclosedEntries, report.problems);
```

<p style="text-align: justify;">

Pass `{ witnesses: [verifierKey], requiredWitnesses: 1 }` as the third argument to require witness cosignatures. The
report has the same fields as the Java `PackageReport`.

</p>

## Verify a Statement and Receipt

```typescript
import { EvidenceStatement, LogReceipt, PublicKey } from "@nexusphere/ledger";

const key = await PublicKey.fromBase64(publicKey);
const statement = EvidenceStatement.parse(await agent.statement(entry.id as string));
const receipt = LogReceipt.parse(await agent.receipt(entry.id as string));
console.log(await statement.verify(key), await receipt.verify(await statement.leafHash(), key));
```

## Verify a Mandate

<p style="text-align: justify;">

`MandateVerifier` reads the issuer's keys from `/public/v1/keys` and its revocation status list, caches both for five
minutes, and reports the same problem codes as the Java and Python SDKs.

</p>

```typescript
import { MandateVerifier } from "@nexusphere/ledger";

const verifier = new MandateVerifier("http://localhost:8090", { audience: "https://supplier.example" });
const check = await verifier.verifyAction(token, "a2a/send", "supplier/sales");
console.log(check.valid, check.claims?.agentId, check.problems.map((p) => p.code));
```

## Command Line

<p style="text-align: justify;">

The package adds `nexusphere-ledger-verify` with the same arguments, output and exit codes (0 valid, 1 invalid, 2
usage error) as the Java verifier jar and the Python SDK.

</p>

Step 1. Verify a package pinned to the ledger's key; the last line must be `Result: VALID`:

```shell
npx nexusphere-ledger-verify --public-key "$(curl -s http://localhost:8090/api/v1/keys -H 'Authorization: Bearer nexusphere-ledger-development-key-change-me' | jq -r '.[] | select(.status == "ACTIVE") | .publicKey')" package.json
```

Step 2. Verify a statement with its receipt, and a mandate for an action:

```shell
npx nexusphere-ledger-verify statement --public-key "{publicKey}" --receipt receipt.cose statement.cose
npx nexusphere-ledger-verify mandate --issuer http://localhost:8090 --action a2a/send --target supplier/sales "{token}"
```

## Reference

| Module       | Contents                                                                                         |
|--------------|--------------------------------------------------------------------------------------------------|
| `client`     | `LedgerClient` for evidence, batches, decisions, `act`, grants, mandates, packages, keys, proofs |
| `package`    | `verifyPackage` and `PackageReport`                                                              |
| `evidence`   | Content and link hashes, `ChainVerifier`, `Checkpoint`                                           |
| `merkle`     | RFC 9162 roots, inclusion and consistency proofs, exported as `merkle`                           |
| `note`       | C2SP signed notes, note keys and `tlog-cosignature/v1` cosignatures                              |
| `cose`       | `CoseSign1`, SCITT `EvidenceStatement` and RFC 9942 `LogReceipt`                                 |
| `mandate`    | SD-JWT VC mandates, `MandateVerifier`, status lists and JWKs                                     |
| `keys`       | Ed25519 keys, key IDs, key rotations and trusted keys from a pinned key                          |
| `canonical`  | Canonical JSON and SHA-256                                                                       |
| `cbor`       | Deterministic CBOR, exported as `cbor`                                                           |

## Test

Step 1. Install the tools, type-check and run the unit tests and the conformance vectors; every test must pass:

```shell
cd ledger/sdk/typescript
npm install
npm run lint
npm test
```

Step 2. Run the same tests against a running ledger, as in the [Quick Start](../../../README.md#quick-start); the
three tests of `a running ledger` must pass instead of being skipped:

```shell
NEXUSPHERE_LEDGER_URL=http://localhost:8090 npm test
```

##

**<p align="center">[Top](#nexusphere-ledger-typescript-sdk)</p>**
