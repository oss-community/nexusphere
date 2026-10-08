# <p align="center">Nexusphere Core Capability</p>

<p align="center">What an actor can do, described so others can find it.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

To work with an organization, agent, machine or service, others first need to know what it offers. A capability is a
typed and versioned description of such an ability, owned by an accountable party and visible only as widely as its
owner decides.

</p>

## Responsibilities

* Define capability types with their schemas
* Register capabilities for an owner of type ORGANIZATION, AGENT, MACHINE, SERVICE or APPLICATION
* Publish and withdraw capabilities (DRAFT, PUBLISHED, WITHDRAWN) and set their visibility: PRIVATE, NETWORK or
  FEDERATED
* Expose `CapabilityDirectory` for discovery, agreements and transactions

## Dependencies

| Depends on                                                                     | Used by                                                       |
|--------------------------------------------------------------------------------|---------------------------------------------------------------|
| `shared`, `authorization`, `identity`, `organization`, `network`, `membership` | `agreement`, `audit`, `bootstrap`, `discovery`, `transaction` |

## API

| Path                                        | Purpose                                                              |
|---------------------------------------------|----------------------------------------------------------------------|
| `/api/v1/capability-types`                  | Define and read capability types                                     |
| `/api/v1/networks/{networkId}/capabilities` | Register, read, publish, change visibility and withdraw capabilities |

## Storage

Tables live in the `capability` schema, created by the Flyway migrations in
`src/main/resources/db/migration/capability`.

##

**<p align="center">[Top](#nexusphere-core-capability)</p>**
