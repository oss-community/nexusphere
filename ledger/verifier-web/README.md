# <p align="center">Nexusphere Package Verifier for the Browser</p>

<p align="center">One HTML file that verifies an evidence package in the browser, with no server and no network.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Build](#build)
* [Verify a Package](#verify-a-package)
* [What It Checks](#what-it-checks)
* [Develop](#develop)
* [Test](#test)

## Purpose

<p style="text-align: justify;">

An auditor or a counterparty who receives an evidence package should not need Java, a command line or the ledger to
check it. The verifier is a React and TypeScript page that runs the [TypeScript SDK](../sdk/typescript/README.md)
verifier on Web Crypto. The build makes one self-contained `index.html` with the script and styles inline. Its
Content Security Policy forbids every connection (`connect-src 'none'`), so the browser itself guarantees that the
package and the keys never leave the page. The file can be mailed, put on a USB stick or opened from disk.

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
cosignatures.

Step 4. Press Verify; the page must show `VALID` with the signing key, the checkpoint, the log, the proofs and the
disclosed entries. Change one character of an entry in the file and verify again; the page must show `INVALID` and
name the entry.

<p style="text-align: justify;">

Without a public key, the page checks the package only against the keys it carries and says so: anyone can build
such a package, so pin the ledger's key whenever the result matters. The page needs a browser with Ed25519 in Web
Crypto: Chrome or Edge 137, Firefox 129, Safari 17 or later.

</p>

## What It Checks

| Check                                                           | Shown as                      |
|-----------------------------------------------------------------|-------------------------------|
| Package format and the signature of the checkpoint              | Signing key and checkpoint    |
| The pinned key, or a key the pinned key endorsed by rotation    | Pinned key                    |
| Every link of the hash chain from the anchor to the checkpoint  | Checked links                 |
| Disclosed entries against their content hash and the scope      | Disclosed entries and scope   |
| Merkle inclusion proofs and SCITT receipts of disclosed entries | Inclusion proofs and receipts |
| Witness cosignatures on the log checkpoint                      | Witnesses                     |

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

Step 1. Run the unit tests; they must all pass:

```shell
cd ledger/verifier-web && npm test
```

Step 2. Build the page and run the browser test, which opens the built file from disk, verifies a package and checks
that no request leaves the page; it must report `1 passed`:

```shell
npm run build && npx playwright install chromium && npm run e2e
```

<p style="text-align: justify;">

When Chromium is already installed elsewhere, skip `playwright install` and set `CHROMIUM_PATH` to its executable.

</p>

##

**<p align="center">[Top](#nexusphere-package-verifier-for-the-browser)</p>**
