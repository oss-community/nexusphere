# <p align="center">Nexusphere Frontend</p>

<p align="center">The web UI of Nexusphere, built with React and TypeScript.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [Getting Started](#getting-started)
* [Sign-in](#sign-in)
* [Principal Dashboard](#principal-dashboard)
* [Ask](#ask)
* [Browser Tests](#browser-tests)

## Purpose

<p style="text-align: justify;">

The ledger's API is enough for agents and scripts, but operators and principals need to see evidence, grants and
consent without curl. This module is a single-page application that talks to the Nexusphere Ledger API through the
same origin, so it needs no CORS on the ledger.

</p>

## Responsibilities

* Operator pages: the ledger head, checkpoints and signing keys, full verification, evidence with filters and entry
  details (including the model name, version, prompt hash and config hash when recorded), erased entries marked with their commitments, agents, grants with their mandates, evidence package export
  and verification, an Approvals page that lists the approval requests of `@approval` policies by status and approves
  or denies them with an optional reason, an Anomalies page that lists the unusual volume, denial and failure rates
  the ledger detected per agent by status and checks for new ones, a Reports page that creates the signed reports of the active compliance
  profiles and dispute packages, A2A exchanges, a Privacy page to erase principals, place and release legal holds
  and run the retention pass, and an Ask page that answers a plain-language question about the evidence with cited
  entries
* Principal dashboard: the landing page of a signed-in principal with the activity of their agents over 7, 30 or 90
  days, the approvals waiting for them, the grants that expire soon, a timeline of the recent actions and the export
  of their own evidence package
* Principal pages: sign-in with the ledger's OpenID Connect provider (authorization code with PKCE), the grants
  waiting for approval, approve, deny, revoke and create grants, the approval requests of the principal's agents to
  approve or deny, the evidence about the principal, and an Ask page that answers questions about the principal's
  own evidence

## Dependencies

| Depends on                                    | Used by |
|-----------------------------------------------|---------|
| the Nexusphere Ledger API (`/api`, `/public`) | none    |

## Getting Started

### Prerequisites

* [Node.js 22](https://nodejs.org)

### Development

Step 1. Start the ledger, for example with the [Quick Start](../README.md#quick-start), and stop its `ledger-ui`
container so port 5173 is free:

```shell
docker stop ledger-ui
```

Step 2. Install the dependencies:

```shell
cd frontend
npm install
```

Step 3. Start the development server:

```shell
npm run dev
```

Step 4. Open http://localhost:5173 and sign in with the operator key `nexusphere-ledger-development-key-change-me`.

<p style="text-align: justify;">

The development server forwards `/api` and `/public` to the ledger at http://localhost:8090, or to `LEDGER_URL` when
it is set.

</p>

### Test

```shell
npm test
```

The browser tests are described in [Browser Tests](#browser-tests).

### Build

```shell
npm run build
```

### Dockerized

<p style="text-align: justify;">

The image builds the application and serves it with nginx on port 80, forwarding `/api` and `/public` to `LEDGER_URL`
(default `http://ledger:8090`). The ledger compose file runs it as `ledger-ui` on http://localhost:5173.

</p>

```shell
docker build -t samanalishiri/nexusphere-frontend:latest frontend
docker run -p 5173:80 -e LEDGER_URL=http://host.docker.internal:8090 samanalishiri/nexusphere-frontend:latest
```

## Sign-in

<p style="text-align: justify;">

The operator signs in with the ledger API key; the key stays in the browser's session storage until sign-out or the
tab closes, and an agent key is refused. A principal signs in with the provider the ledger trusts: the UI reads the
issuer and client ID from `/public/v1/oidc` and uses the authorization code flow with PKCE, so the provider needs a
public client that allows `http://localhost:5173/*` as redirect URI. The Keycloak realm in the ledger compose file has
one, `nexusphere-ledger`, with the principals `alice` and `bob`.

</p>

## Principal Dashboard

<p style="text-align: justify;">

A principal lands on `/my`, the Dashboard, after sign-in. It shows what agents did on the principal's behalf and what
waits for the principal, from the principal endpoints of the ledger, all called with the principal's OpenID Connect
token. Days are UTC days, the same as the ledger counts them.

</p>

| Card               | Shows                                                                                                | Endpoint                                |
|--------------------|------------------------------------------------------------------------------------------------------|-----------------------------------------|
| Activity           | totals (actions, allowed, denied, failed), one bar per day of 7, 30 or 90 days and a per-agent table | `GET /api/v1/principal/activity?days=N` |
| Waiting for you    | the pending approvals the principal can answer, with Approve and Deny                                | `GET /api/v1/principal/approvals`       |
| Grants             | the number of active grants and the ones expiring within 7 days                                      | `GET /api/v1/principal/grants`          |
| Export my evidence | an evidence package of the principal, optionally for one agent and a sequence range                  | `POST /api/v1/principal/packages`       |
| Recent actions     | the newest evidence about the principal grouped by day; Load more fetches the older page before it   | `GET /api/v1/principal/evidence/recent` |

<p style="text-align: justify;">

The Recent actions card links to `/my/ask`, the principal's [Ask](#ask) page.

</p>

<p style="text-align: justify;">

The bar chart is plain HTML and CSS: every day of the period has a bar, days without evidence included, scaled to the
busiest day, and each bar names its day and counts in its title and accessible label. The export downloads
`evidence-package-<checkpoint sequence>.json`; the package is scoped to the signed-in principal by the ledger and can
be verified offline in the Auditor Portal (`ledger/verifier-web`) with the ledger's public key.

</p>

<p style="text-align: justify;">

The timeline reads the evidence newest first: the first page holds the latest 50 entries, and Load more asks for the
page before the oldest one shown with `before=<nextBefore>` until the ledger answers no `nextBefore`.

</p>

## Ask

<p style="text-align: justify;">

The Ask page answers a question in plain language from the evidence. The operator opens it at `/ask` and the question
covers the whole ledger; a principal opens it at `/my/ask`, from the navigation or the Recent actions card of the
Dashboard, and the ledger answers only from the principal's own evidence. Both routes share one page that is given its
endpoint.

</p>

| Route     | Signed in as | Endpoint                         |
|-----------|--------------|----------------------------------|
| `/ask`    | operator     | `POST /api/v1/ask`               |
| `/my/ask` | principal    | `POST /api/v1/principal/ask`     |

<p style="text-align: justify;">

The question has at most 500 characters, with a counter under the text box and example questions to pick. The answer
is shown sentence by sentence, each followed by `#<sequence>` chips for the entries it cites; a chip highlights and
scrolls to its row in the Cited evidence table, which shows the entry with its content, link and inclusion checks, or
`Verified` when all three passed. The search plan the ledger used is shown as labeled chips, and a footer names the
model, the number of entries considered, the checkpoint size, the sequence the question was recorded as and how many
sentences without valid citations were removed. Entries that failed verification are listed in a warning and were not
used. A rejected question (400) or an unavailable model (502) shows the ledger's message.

</p>

## Browser Tests

<p style="text-align: justify;">

The Playwright tests in `e2e` build the application, serve it with `vite preview` on port 4174 and drive Chromium
against it. They need no ledger and no OpenID Connect provider: every `/api` and `/public` request is answered by
`page.route`, and the principal is signed in by placing an oidc-client-ts user in session storage under
`oidc.user:<issuer>:<client ID>`, the issuer and client ID that the mocked `/public/v1/oidc` returns. The operator
test signs in with the API key on the sign-in page.

</p>

| Test                | Checks                                                                                                                                                                                |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `dashboard.spec.ts` | totals and one bar per day, approving sends the POST and empties the queue, the export downloads a package whose scope names the principal, the newest timeline entries and Load more |
| `operator.spec.ts`  | operator sign-in with the API key and the Overview page                                                                                                                               |
| `ask.spec.ts`       | the operator asks a question, sees the model error and then the cited answer, plan, warning and footer, and a citation chip highlights its row; the principal asks from the Dashboard |

Step 1. Install the dependencies:

```shell
cd frontend
npm ci
```

The command must end without errors.

Step 2. Point Playwright at a Chromium binary, or install its own once with `npx playwright install chromium`:

```shell
export CHROMIUM_PATH=/path/to/chrome
```

`"$CHROMIUM_PATH" --version` must print a Chromium version.

Step 3. Run the browser tests:

```shell
npm run e2e
```

Playwright must report `7 passed`.

##

**<p align="center">[Top](#nexusphere-frontend)</p>**
