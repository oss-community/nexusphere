# <p align="center">Nexusphere Auditor Portal</p>

<p align="center">One HTML file that loads an evidence package or a dispute package and verifies it fully in the browser, the chain, log proofs, witnesses, receipts and reports, with no server and no network.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Build](#build)
* [Verify a Package](#verify-a-package)
* [Investigate a Package](#investigate-a-package)
* [Verify a Dispute Package](#verify-a-dispute-package)
* [Verify an Index Proof](#verify-an-index-proof)
* [Verify a Track Record](#verify-a-track-record)
* [What It Checks](#what-it-checks)
* [Develop](#develop)
* [Test](#test)

## Purpose

<p style="text-align: justify;">

An auditor, an insurer or a counterparty who receives an evidence package should not need Java, a command line or
the ledger to check it, and should be able to go through it entry by entry. The Auditor Portal is a React and
TypeScript page that runs the [TypeScript SDK](../sdk/typescript/README.md) verifier on Web Crypto. It verifies the
whole package, the hash chain, the log proofs, the witnesses and the receipts, and then opens an auditor workspace:
the entries with filters and the checks of each entry recomputed in the browser, the chain from the anchor to the
checkpoint, the log with each signature line, a signed report checked against the package, and an audit worksheet to
download or print. It also reads the dispute package ZIP that `POST /api/v1/disputes` answers, and checks index proofs
and agent track records. The build makes one self-contained `index.html` with the script and styles inline. Its
Content Security Policy forbids every connection (`connect-src 'none'`), so the browser itself guarantees that no
package, report or key leaves the page. The file can be mailed, put on a USB stick or opened from disk.

</p>

## Build

Step 1. Check Node; the version must be 20 or later:

```shell
node --version
```

Step 2. Install the dependencies:

```shell
cd ledger/verifier-web && npm ci
```

Step 3. Build the page:

```shell
npm run build
```

Step 4. Check the result; `dist` must hold only `index.html`:

```shell
ls dist
```

## Verify a Package

Step 1. Export a package and read the ledger's public key, as the operator:

```shell
curl -s -X POST http://localhost:8090/api/v1/packages -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"invoice-agent"}' > package.json
curl -s http://localhost:8090/api/v1/keys -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" | jq -r '.[] | select(.status == "ACTIVE") | .publicKey'
```

Step 2. Open `ledger/verifier-web/dist/index.html` in a browser, from disk or any web server.

Step 3. Choose or drop `package.json`, paste the public key, and add witness keys if the log must carry their
cosignatures. To apply key revocations, also choose the key list saved from `GET /api/v1/keys`. When the ledger
[timestamps its checkpoints](../README.md#checkpoint-timestamps), choose the authority's `tsa-chain.pem` and tick
Require a timestamp. When it [anchors them in Rekor](../README.md#rekor-anchoring), paste the log's verifier key in
Rekor log keys and tick Require a Rekor entry.

Step 4. Press Verify; the page must show `VALID` and, in the Overview tab, the signing key, the checkpoint, its
timestamp and Rekor entry, the log and the proofs. The Entries tab lists the disclosed entries, with `erased` in place
of the principal and target of an entry whose personal fields were erased. When the entries name their
[model](../README.md#model-and-prompt), the table adds a Model column with its name and version, and a package exported with `"includePrompts": true` adds a Prompts line with the number of prompt texts
that match the hashes their entries name; a package that carries `policies` adds a Policies line with the number of
Cedar policy set texts that match the hashes their entries name in their `policy.hash` attribute. Change one character of an entry in the file and verify again; the page must show `INVALID` and name the entry.

<p style="text-align: justify;">

Without a public key, the page checks the package only against the keys it carries and says so: anyone can build
such a package, so pin the ledger's key whenever the result matters. The page needs a browser with Ed25519 in Web
Crypto: Chrome or Edge 137, Firefox 129, Safari 17 or later.

</p>

## Verify an Index Proof

<p style="text-align: justify;">

The page also takes an [index proof](../README.md#index-proofs), which it recognises by its format
`nexusphere-ledger/index-proof/v1`: that a term occurs in the evidence up to a checkpoint, with how often, or that it
never did. It runs the same checks as `nexusphere-ledger-verify index`, without `--package`: the term and its key, the
signature of the index with the keys the proof carries, reached from the pinned key, a revoked key from the key list,
and the path from the leaf to the signed root.

</p>

Step 1. Ask for a proof that the payments agent never deleted a file, as the operator:

```shell
curl -s -X POST http://localhost:8090/api/v1/index/proofs -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"term":{"type":"action","agentId":"payments-agent","action":"files/delete"}}' > proof.json
```

Step 2. Open `ledger/verifier-web/dist/index.html`, choose or drop `proof.json` and paste the ledger's public key.

Step 3. Press Verify; the page must show `VALID` with the Term `action payments-agent files/delete`, the Checkpoint
with its head hash, Signed by with the key ID, and the Finding `no entry up to sequence N`, or the count with the first
and last sequence when the term occurs. Change `files/delete` to `files/remove` in the file and verify again; the page
must show `INVALID` and `the key does not belong to the term`.

## Verify a Track Record

<p style="text-align: justify;">

The page also takes an [agent track record](../README.md#agent-track-records), which it recognises as an SD-JWT VC
whose `vct` is `urn:nexusphere:vct:agent-track-record:1`. It checks the `dc+sd-jwt` type, the disclosures, the
signature with the pinned ledger key or a key it endorsed in the key list, the validity period and, when the record
names an agent key in `cnf`, its key binding. With an index proof for the `agent` term, it also checks that the proof
verifies and gives the same checkpoint, index root, count and sequences, as `nexusphere-ledger-verify track-record
--index-proof` does. The page has no issuer list: the pinned key is the trust anchor.

</p>

Step 1. Get a track record and an index proof for the agent at the same checkpoint, as in
[Agent Track Records](../README.md#agent-track-records).

Step 2. Open `ledger/verifier-web/dist/index.html`, choose or drop `track-record.jwt` or paste its text in Or paste a
track record, paste the ledger's public key and choose `agent-proof.json` as the index proof.

Step 3. Press Verify; the page must show `VALID` with the Credential, Issuer, Agent, Audience, Valid, Checkpoint,
Index root, Entries, Outcomes, Principals and Actions, `withheld` for each claim the presentation does not disclose,
and Index proof `N entries at checkpoint S, matches the track record`. Verify a proof for another agent; the page must
show `INVALID` and `The index proof is not about the agent quote-agent`.

## Investigate a Package

<p style="text-align: justify;">

Below the verdict of an evidence package, valid or not, the page opens an auditor workspace with six tabs. Overview
holds the summary of the verification. Entries lists the disclosed entries with filters and opens each one with its
checks. Chain lists the links from the anchor to the checkpoint in pages of 500, so a package of 200,000 links stays
usable. Log shows the log checkpoint and each signature line. Report checks a signed report against the package.
Export downloads the audit worksheet and prints. The tabs follow the ARIA tabs pattern and switch with the arrow keys.

</p>

Step 1. Verify a package as in [Verify a Package](#verify-a-package), with the witness keys if the log carries
cosignatures; the page must show the verdict and below it the tabs Overview, Entries, Chain, Log, Report and Export,
with Overview open.

Step 2. Open Entries; the page must show `N of N entries` and the table of the disclosed entries.

Step 3. Choose an agent in Agent, and narrow further with Principal, Action, Decision, Outcome, From and To on the day
the entry occurred, or a text in Search target and attributes; the count must change to `M of N entries` and the table
must list only the matching entries.

Step 4. Click an entry; the page must open the panel `Entry S` with every field of the entry, its attributes, model,
delegation, input and output hashes, commitments, previous hash and hash, and the checks computed in the browser:
Content hash, Entry hash, Hash link, Inclusion proof, SCITT statement and SCITT receipt, each `passed`, `failed` or
`not present`, and `All present checks passed`.

Step 5. Change one character of the target of that entry in the file and verify again; the page must show `INVALID`,
and the same entry must show Content hash and Entry hash `failed` and `A check failed`.

Step 6. Open Chain; the page must show where the chain starts, the anchor or the genesis hash, where it ends, the
number of links and the first 500 with their sequence, short hash, previous hash and whether the entry is disclosed.
Enter a sequence in Go to sequence and press Go; the page must show the page that holds it. Click `disclosed`; the
page must open that entry in Entries.

Step 7. Open Log; the page must show the origin, tree size and root of the log checkpoint, the inclusion proofs and
receipts, the timestamp and Rekor entry, and one row per signature line with its name, key hash, kind and whether it
verified, with the cosignature time of each witness whose key was given.

Step 8. Open Report and choose the `report.json` that `POST /api/v1/reports/{id}` answered for this package; the page
must show `VALID`, the title, subject, signing key, evidence and section counts, and each section with its reference,
requirement, `SHOWN`, `PARTLY_SHOWN` or `NOT_SHOWN`, findings and entry sequences. Click a sequence; the page must
open that entry in Entries. A report for another package must show `INVALID` and its problems.

Step 9. Open Export and press Download audit worksheet (CSV); the browser must save `audit-worksheet-S.csv` with the
entries the filters of Entries show, one row each with its fields, its hash and the result of each check. The file is
made on the page from a `Blob`; nothing is sent.

Step 10. Press Print; the print preview must show the verdict, the overview and the filtered entries, without the form
and the tabs.

## Verify a Dispute Package

<p style="text-align: justify;">

A [dispute package](../README.md#reports-and-dispute-packages) is the ZIP archive that `POST /api/v1/disputes` answers.
The page reads it in the browser, stored and deflated entries alike, and takes the files by their names, as
`nexusphere-ledger-verify dispute` does: `package.json` as the package, `report.json` as the report of the Report tab,
`keys.json` as the key list, `timestamp-authority.pem` as the timestamp authority chain, the `key` of `rekor.json` as
the Rekor log key and `witnesses.txt` as the witness keys, checked but not required unless Required witnesses says so.
The archive comes from the ledger itself, so paste the ledger's public key from a copy obtained on your own.

</p>

Step 1. Create a dispute package, as the operator:

```shell
curl -s -X POST http://localhost:8090/api/v1/disputes -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" -H "Content-Type: application/json" -d '{"agentId":"payments-agent","title":"Invoice 4711 paid twice"}' -o dispute.zip
```

Step 2. Open `ledger/verifier-web/dist/index.html` and choose or drop `dispute.zip`; the page must show
`Dispute package dispute.zip: took package.json, report.json, keys.json` with the other files it took, and the files it
did not use, such as `README.txt`, `report.html` and `compliance.json`. The key list must show
`keys.json from the dispute package`, and the witness and Rekor fields must hold the keys of the archive.

Step 3. Paste the ledger's public key and press Verify; the page must show `VALID`.

Step 4. Open Report; the page must show `report.json from the dispute package` and `VALID` with the sections of the
dispute report.

## What It Checks

| Check                                                            | Shown as                        |
|------------------------------------------------------------------|---------------------------------|
| Package format and the signature of the checkpoint               | Signing key and checkpoint      |
| The pinned key, or a key the pinned key endorsed by rotation     | Pinned key                      |
| Every link of the hash chain from the anchor to the checkpoint   | Checked links                   |
| Disclosed entries against their content hash and the scope       | Disclosed entries and scope     |
| Merkle inclusion proofs and SCITT receipts of disclosed entries  | Inclusion proofs and receipts   |
| Witness cosignatures on the log checkpoint                       | Witnesses                       |
| Revoked keys, valid only where witnesses predate the compromise  | Revoked keys                    |
| The RFC 3161 timestamp of the checkpoint and the authority chain | Timestamp                       |
| The Rekor entry of the checkpoint and its inclusion proof        | Rekor                           |
| Prompt texts against their hash and the entries that name them   | Prompts                         |
| Policy set texts against their hash and the entries naming them  | Policies                        |
| The content hash and entry hash of one entry, recomputed         | Entry: Content hash, Entry hash |
| The hash link of one entry to the link before it or the anchor   | Entry: Hash link                |
| The inclusion proof of one entry against the log checkpoint root | Entry: Inclusion proof          |
| The SCITT statement and receipt of one entry                     | Entry: SCITT statement, receipt |
| Each signature line of the log checkpoint with the given keys    | Log: signature lines            |
| A signed report: signature, checkpoint, scope and entries digest | Report: verdict and sections    |
| The term, the signed index and the path of an index proof        | Term, Checkpoint, Signed by     |
| The count and sequences of a term, or that it never occurred     | Finding                         |
| Type, disclosures, signature and validity of a track record      | Credential, Signed by, Valid    |
| The key binding of a track record bound to an agent key          | Agent key                       |
| A track record against an index proof for its agent              | Index proof                     |

<p style="text-align: justify;">

The checks are the same as those of the [Offline Verifier](../verifier/README.md), so a package that is valid on the
command line is valid here.

</p>

## Develop

Step 1. Start the development server; the page opens on http://localhost:5174:

```shell
cd ledger/verifier-web && npm run dev
```

## Test

Step 1. Run the unit tests, which cover the page, the per-entry checks, the filters, the worksheet, the signature lines
and the ZIP reader; they must report `58 passed`:

```shell
cd ledger/verifier-web && npm test
```

Step 2. Build the page and run the browser tests, which open the built file from disk, verify a package, check that no
request leaves the page, that the prompt texts and models show, that an index proof verifies and that a track record
matches its index proof, and, in the auditor workspace, filter the entries by agent and open one with every check
passed, show the failed checks of a tampered entry, verify a report and open the entries it names, read a dispute
package and download the audit worksheet; they must report `9 passed`:

```shell
npm run build && npx playwright install chromium && npm run e2e
```

<p style="text-align: justify;">

When Chromium is already installed elsewhere, skip `playwright install` and set `CHROMIUM_PATH` to its executable.

</p>

##

**<p align="center">[Top](#nexusphere-auditor-portal)</p>**
