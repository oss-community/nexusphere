# <p align="center">Nexusphere Core Trust</p>

<p align="center">What one party believes about another, and for what.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Before two networks or parties work together, one of them has to state that it trusts the other, for a given scope and
level. Trust is directional and revocable, and it does not grant permission on its own; authorization reads it as
evidence.

</p>

## Responsibilities

* Establish and revoke trust relationships between networks, organizations and identities, with a level of LOW, MEDIUM
  or HIGH
* Evaluate whether a party trusts another for a scope
* Feed trust evidence to authorization through `TrustEvidencePort`
* Publish `TrustEstablished` and `TrustRevoked`

## Dependencies

| Depends on                                                                     | Used by                   |
|--------------------------------------------------------------------------------|---------------------------|
| `shared`, `authorization`, `organization`, `network`, `identity`, `membership` | `bootstrap`, `federation` |

## API

| Path                                               | Purpose                                    |
|----------------------------------------------------|--------------------------------------------|
| `/api/v1/networks/{networkId}/trust-relationships` | Establish, read, revoke and evaluate trust |

## Storage

Tables live in the `trust` schema, created by the Flyway migrations in `src/main/resources/db/migration/trust`.

##

**<p align="center">[Top](#nexusphere-core-trust)</p>**
