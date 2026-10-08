# <p align="center">Nexusphere Core Organization</p>

<p align="center">Institutions that take part in a network.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Agents, machines and services act for someone accountable. Organizations are those accountable institutions inside a
network. The module keeps them deliberately flat: no departments or teams are forced on a network.

</p>

## Responsibilities

* Register, rename and deactivate organizations inside a network (ACTIVE, DEACTIVATED)
* Publish `OrganizationRegistered`, `OrganizationRenamed` and `OrganizationDeactivated`
* Expose `OrganizationDirectory` for ownership checks in identity, capability and trust

## Dependencies

| Depends on          | Used by                                                      |
|---------------------|--------------------------------------------------------------|
| `shared`, `network` | `bootstrap`, `capability`, `identity`, `membership`, `trust` |

## API

| Path                                         | Purpose                                             |
|----------------------------------------------|-----------------------------------------------------|
| `/api/v1/networks/{networkId}/organizations` | Register, read, rename and deactivate organizations |

## Storage

Tables live in the `organization` schema, created by the Flyway migrations in
`src/main/resources/db/migration/organization`.

##

**<p align="center">[Top](#nexusphere-core-organization)</p>**
