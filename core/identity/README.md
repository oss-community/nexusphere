# <p align="center">Nexusphere Core Identity</p>

<p align="center">Who or what exists, and how it proves it.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Humans, services, applications, agents and machines all need to be known before they can do anything. This module
records them and their credentials, and nothing more: an identity has no authority by itself. Authority comes later from
membership, roles and delegation.

</p>

## Responsibilities

* Create identities of type HUMAN, SERVICE, APPLICATION, AGENT or MACHINE, and suspend or reactivate them
* Record the owning organization of agents and machines, which is accountable for them
* Issue, rotate and revoke credentials (ACTIVE, EXPIRED, REVOKED) that are exchanged for a bearer token
* Expose `IdentityDirectory` and `CredentialVerifier` to the rest of the core

## Dependencies

| Depends on                          | Used by                                                                    |
|-------------------------------------|----------------------------------------------------------------------------|
| `shared`, `network`, `organization` | `audit`, `authorization`, `bootstrap`, `capability`, `membership`, `trust` |

## API

| Path                 | Purpose                                                                                   |
|----------------------|-------------------------------------------------------------------------------------------|
| `/api/v1/identities` | Create, read, suspend and activate identities; issue, list, rotate and revoke credentials |

## Storage

Tables live in the `identity` schema, created by the Flyway migrations in `src/main/resources/db/migration/identity`.

##

**<p align="center">[Top](#nexusphere-core-identity)</p>**
