# <p align="center">Nexusphere Ledger TypeScript SDK</p>

<p align="center">Record evidence from a Node or browser agent and verify everything the ledger produces, offline.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Install](#install)
* [Record Evidence](#record-evidence)
* [Record the Model and Prompt](#record-the-model-and-prompt)
* [Decide and Report](#decide-and-report)
* [Verify a Package](#verify-a-package)
* [Verify a Report](#verify-a-report)
* [Verify a Statement and Receipt](#verify-a-statement-and-receipt)
* [Verify a Mandate](#verify-a-mandate)
* [Present a Key-Bound Mandate](#present-a-key-bound-mandate)
* [Check a Signed Request](#check-a-signed-request)
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

## Record the Model and Prompt

<p style="text-align: justify;">

Evidence and decisions may name the [model](../../README.md#model-and-prompt) the agent ran in `model`: its `name`,
`version`, and the lowercase SHA-256 `promptHash` and `configHash` of its system prompt and configuration. The content
hash covers the model; entries without one hash as before. `client.storePrompt(text)` stores a prompt or configuration
text and resolves to its hash, and `client.prompt(hash)` reads it back, as the operator.

</p>

Step 1. Store the system prompt and record an action with its model; the answer must show the model:

```typescript
const promptHash = await agent.storePrompt("You pay approved invoices of Acme up to 500 EUR.");
const modelled = await agent.record({
  agentId: "invoice-agent", principalId: "acme", action: "tools/call", outcome: "SUCCEEDED",
  model: { name: "claude-sonnet-5-5", version: "2026-09-01", promptHash },
});
console.log(modelled.model);
```

Step 2. Export a package with the prompt texts its entries name; `disclosedPrompts` must be `1`:

```typescript
import { verifyPackage } from "@nexusphere/ledger";

const pkg = await operator.exportPackage({ agentId: "invoice-agent", includePrompts: true });
console.log((await verifyPackage(pkg, await operator.activePublicKey())).disclosedPrompts);
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

Pass `{ witnesses: [verifierKey], requiredWitnesses: 1 }` as the third argument to require witness cosignatures, and
`keys` with the list from `GET /api/v1/keys` to apply key revocations, as the
[Ledger README](../../README.md#key-revocation) describes; the command takes it as `--keys keys.json`. The report has
the same fields as the Java `PackageReport`. `report.complianceProfiles` lists the id and digest of each
[compliance profile](../../README.md#compliance-profiles) the checkpoint signs, and `client.compliance()` returns the
active profiles and their combined rules from the ledger. An entry whose personal fields were
[erased](../../README.md#erasure) still verifies from its commitments, and `client.erasePrincipal(principalId, reason)`
asks the ledger to erase a principal. A package exported with `includePrompts: true` carries `prompts`; each text must
match its hash and be named by a disclosed entry, and `report.disclosedPrompts` counts those that do. `placeLegalHold`, `releaseLegalHold`, `legalHolds` and `sweepRetention` manage
[legal holds](../../README.md#retention-and-legal-holds) and the retention pass.

</p>

<p style="text-align: justify;">

A checkpoint may carry an RFC 3161 token from a timestamp authority, as the
[Ledger README](../../README.md#checkpoint-timestamps) describes. Pass `timestampCertificates` with the authority's
chain from `readCertificates(pem)` to pin the authority, and `requireTimestamp: true` to reject a checkpoint without a
token; the command takes them as `--timestamp-certificates chain.pem` and `--require-timestamp`. The report gives
`timestampedAt` and `timestampAuthority`, and `verifyTimestamp(token, sha256, certificates)` checks a token on its own.

</p>

```typescript
import { readFileSync } from "node:fs";
import { readCertificates, verifyPackage } from "@nexusphere/ledger";

const timestampCertificates = readCertificates(readFileSync("tsa-chain.pem", "utf8"));
const report = await verifyPackage(pkg, publicKey, { timestampCertificates, requireTimestamp: true });
console.log(report.timestampedAt, report.timestampAuthority);
```

<p style="text-align: justify;">

A checkpoint may also carry a Rekor v2 `hashedrekord` entry, as the [Ledger README](../../README.md#rekor-anchoring)
describes. Pass `rekorKeys` with the Rekor log's C2SP verifier keys to pin the log, and `requireRekor: true` to reject a
checkpoint without an entry; the command takes them as `--rekor-key <verifier key>` and `--require-rekor`. The report
gives `rekorLogIndex` and `rekorOrigin`, and `verifyRekorEntry(entry, signedBytes, ledgerKey, rekorKeys)` checks an
entry on its own.

</p>

```typescript
import { verifyPackage } from "@nexusphere/ledger";

const report = await verifyPackage(pkg, publicKey, { rekorKeys: [rekorVerifierKey], requireRekor: true });
console.log(report.rekorLogIndex, report.rekorOrigin);
```

## Verify a Report

<p style="text-align: justify;">

The ledger signs [reports and dispute packages](../../README.md#reports-and-dispute-packages) that map the evidence of
a package to requirements. `client.reports()` lists the reports the ledger offers, `client.createReport(id, scope)`
and `client.createReportHtml(id, scope)` make one, and `client.createDispute({ title, description, ...scope })` returns
the dispute package as zip bytes; each takes `includePrompts: true` in its scope to add the prompt texts. `verifyReport(report, pkg, { publicKey, keys })` checks the signature with the keys
the package is trusted with, and that the report names the package's checkpoint, scope and disclosed entries; verify
the package itself with `verifyPackage`.

</p>

```typescript
import { verifyPackage, verifyReport } from "@nexusphere/ledger";

const pkg = await operator.exportPackage({ agentId: "invoice-agent" });
const report = await operator.createReport("dispute", { agentId: "invoice-agent" });
const check = await verifyReport(report, pkg, { publicKey });
console.log((await verifyPackage(pkg, publicKey)).valid && check.valid, check.statuses, check.problems);
```

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

## Present a Key-Bound Mandate

<p style="text-align: justify;">

An agent with its own key registers the public half with `setSigningKey`; mandates issued afterwards name it in
`cnf`. `presentBound` adds a KB-JWT signed by the agent for an audience and a nonce, and `verifyBound` checks it. For
the A2A gateway the nonce is the hex SHA-256 of the request body.

</p>

```typescript
import { MandateVerifier, PrivateKey, hashOf, presentBound } from "@nexusphere/ledger";

const key = await PrivateKey.generate();
await agent.setSigningKey("invoice-agent", key.publicKey);
const token = (await agent.issueMandate(grantId, "https://supplier.example")).token as string;
const presented = await presentBound(token, key, "https://supplier.example", await hashOf(body));
const check = await new MandateVerifier("http://localhost:8090", { audience: "https://supplier.example" })
  .verifyBound(presented, await hashOf(body));
console.log(check.valid, check.claims?.holderKey);
```

## Check a Signed Request

<p style="text-align: justify;">

Every request the ledger forwards to an MCP server or an A2A agent carries an RFC 9421 HTTP message signature.
`verifyRequest` checks it with the ledger's published keys and resolves to the key ID, `created` and `nonce`; it
rejects with `InvalidHttpSignature` when the signature is missing, stale or does not match the method, address or
body. `signRequest` makes the same headers.

</p>

```typescript
import { JwksKeys, httpFetcher, verifyRequest } from "@nexusphere/ledger";

const keys = new JwksKeys(httpFetcher());
const verified = await verifyRequest("POST", "http://files-mcp:3000/mcp", request.headers, body,
  (kid) => keys.resolve("http://localhost:8090", kid));
console.log(verified.keyId, verified.nonce);
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

Step 2. Verify a package whose checkpoint must carry a timestamp from the pinned authority; the output must show
`Timestamp   : ... (pinned)`:

```shell
npx nexusphere-ledger-verify --timestamp-certificates tsa-chain.pem --require-timestamp package.json
```

Step 3. Verify a package whose checkpoint must be anchored in the pinned Rekor log; the output must show
`Rekor       : entry ... (pinned)`:

```shell
npx nexusphere-ledger-verify --rekor-key "{rekorVerifierKey}" --require-rekor package.json
```

Step 4. Verify a statement with its receipt, and a mandate for an action:

```shell
npx nexusphere-ledger-verify statement --public-key "{publicKey}" --receipt receipt.cose statement.cose
npx nexusphere-ledger-verify mandate --issuer http://localhost:8090 --action a2a/send --target supplier/sales "{token}"
```

Step 5. Verify a dispute package, and a report against its package; the last line must be `Result: VALID`:

```shell
npx nexusphere-ledger-verify dispute --public-key "$LEDGER_PUBLIC_KEY" dispute.zip
npx nexusphere-ledger-verify report --public-key "$LEDGER_PUBLIC_KEY" --package package.json report.json
```

<p style="text-align: justify;">

`dispute` takes `keys.json`, the timestamp authority chain, the Rekor log key and the witness keys from the archive
when no option gives them, and lists the last three on the `Archive` line; compare them with copies of your own. It
requires no witness cosignature unless `--witness` or `--witnesses-required` is given.

</p>

## Reference

| Module      | Contents                                                                                         |
|-------------|--------------------------------------------------------------------------------------------------|
| `client`    | `LedgerClient` for evidence, decisions, `act`, grants, mandates, prompts, packages, keys, proofs |
| `package`   | `verifyPackage` and `PackageReport`                                                              |
| `report`    | Signed reports: `verifyReport`, `ReportCheck`, `reportSignedBytes` and `entriesDigest`           |
| `evidence`  | Content and link hashes, commitments, `ChainVerifier`, `Checkpoint` with its compliance profiles |
| `timestamp` | RFC 3161 tokens, X.509 certificates, `readCertificates` and `verifyTimestamp`                    |
| `rekor`     | Rekor v2 `hashedrekord` entries: `verifyRekorEntry` and `rekorRequest`                           |
| `merkle`    | RFC 9162 roots, inclusion and consistency proofs, exported as `merkle`                           |
| `note`      | C2SP signed notes, note keys and `tlog-cosignature/v1` cosignatures                              |
| `cose`      | `CoseSign1`, SCITT `EvidenceStatement` and RFC 9942 `LogReceipt`                                 |
| `mandate`   | SD-JWT VC mandates, `MandateVerifier`, status lists and JWKs                                     |
| `httpsig`   | RFC 9421 HTTP message signatures: `signRequest`, `verifyRequest`, `contentDigest`                |
| `keys`      | Ed25519 keys, key IDs, key rotations and trusted keys from a pinned key                          |
| `canonical` | Canonical JSON and SHA-256                                                                       |
| `cbor`      | Deterministic CBOR, exported as `cbor`                                                           |

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
