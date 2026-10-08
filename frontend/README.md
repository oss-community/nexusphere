# <p align="center">Nexusphere Frontend</p>

<p align="center">The web UI of Nexusphere, built with React and TypeScript.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [Getting Started](#getting-started)
* [Sign-in](#sign-in)

## Purpose

<p style="text-align: justify;">

The ledger's API is enough for agents and scripts, but operators and principals need to see evidence, grants and
consent without curl. This module is a single-page application that talks to the Nexusphere Ledger API through the
same origin, so it needs no CORS on the ledger.

</p>

## Responsibilities

* Operator pages: the ledger head, checkpoints and signing keys, full verification, evidence with filters and entry
  details, agents, grants with their mandates, evidence package export and verification, and A2A exchanges
* Principal pages: sign-in with the ledger's OpenID Connect provider (authorization code with PKCE), the grants
  waiting for approval, approve, deny, revoke and create grants, and the evidence about the principal

## Dependencies

| Depends on                                    | Used by |
|-----------------------------------------------|---------|
| the Nexusphere Ledger API (`/api`, `/public`) | none    |

## Getting Started

### Prerequisites

* [Node.js 22](https://nodejs.org)

### Development

```shell
cd frontend
npm install
npm run dev
```

<p style="text-align: justify;">

The development server runs on http://localhost:5173 and forwards `/api` and `/public` to the ledger at
http://localhost:8090, or to `LEDGER_URL` when it is set.

</p>

### Test

```shell
npm test
```

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

##

**<p align="center">[Top](#nexusphere-frontend)</p>**
