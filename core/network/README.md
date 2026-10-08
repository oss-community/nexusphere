# <p align="center">Nexusphere Core Network</p>

<p align="center">Sovereign networks and their lifecycle.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Nexusphere is a federation of independently governed spaces, not one global platform. A network is that space: its own
administration, identities, data and policy. Every other concept lives inside a network, so this module is the root of
sovereignty and the first thing created.

</p>

## Responsibilities

* Create a network and move it through PENDING, ACTIVE, SUSPENDED and ARCHIVED
* Publish `NetworkCreated`, `NetworkActivated`, `NetworkSuspended` and `NetworkArchived`
* Expose `NetworkDirectory` so other modules can check that a network exists and is active

## Dependencies

| Depends on | Used by                                                                                                                                |
|------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `shared`   | `authorization`, `bootstrap`, `capability`, `delegation`, `discovery`, `federation`, `identity`, `membership`, `organization`, `trust` |

## API

| Path               | Purpose                                              |
|--------------------|------------------------------------------------------|
| `/api/v1/networks` | Create, read, activate, suspend and archive networks |

## Storage

Tables live in the `network` schema, created by the Flyway migrations in `src/main/resources/db/migration/network`.

##

**<p align="center">[Top](#nexusphere-core-network)</p>**
