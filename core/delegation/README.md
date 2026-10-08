# <p align="center">Nexusphere Core Delegation</p>

<p align="center">Authority lent by one principal to another, within limits.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Agents act on behalf of people and organizations, and that has to be explicit. A delegation grants a subset of the
delegator's actions to another principal, bounded in time, constrained by capability type, network and resource type,
revocable, and never deeper than one level. Permission alone never creates a delegation.

</p>

## Responsibilities

* Grant, suspend, resume and revoke delegations (ACTIVE, SUSPENDED, REVOKED, EXPIRED)
* Reject delegations that exceed the delegator's own authority
* Give authorization the delegation evidence of a request through `DelegationEvidencePort`
* Publish `DelegationGranted`, `DelegationSuspended`, `DelegationResumed` and `DelegationRevoked`

## Dependencies

| Depends on                                         | Used by     |
|----------------------------------------------------|-------------|
| `shared`, `authorization`, `membership`, `network` | `bootstrap` |

## API

| Path                                       | Purpose                                             |
|--------------------------------------------|-----------------------------------------------------|
| `/api/v1/networks/{networkId}/delegations` | Grant, read, suspend, resume and revoke delegations |

## Storage

Tables live in the `delegation` schema, created by the Flyway migrations in
`src/main/resources/db/migration/delegation`.

##

**<p align="center">[Top](#nexusphere-core-delegation)</p>**
