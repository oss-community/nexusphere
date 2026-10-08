# <p align="center">Nexusphere Core Membership</p>

<p align="center">Where an identity takes part, and the principal it acts as.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

An identity alone cannot act. It acts through a membership in a network, as a MEMBER or an ADMINISTRATOR, and that
pairing is the principal every authorization decision works on. Keeping membership separate from identity means one
identity can take part in several networks without leaking authority between them.

</p>

## Responsibilities

* Grant and terminate memberships, with at most one active membership per identity and network
* Resolve the `PrincipalContext` of a request through `PrincipalResolver`, and deny access to networks the caller is not
  a member of
* List the members of a network
* Publish `MembershipActivated`, `MembershipTerminated` and `NetworkAccessDenied`

## Dependencies

| Depends on                                      | Used by                                                                                                                                          |
|-------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `shared`, `identity`, `organization`, `network` | `agreement`, `audit`, `authorization`, `bootstrap`, `capability`, `delegation`, `discovery`, `federation`, `integration`, `transaction`, `trust` |

## API

| Path                                       | Purpose                               |
|--------------------------------------------|---------------------------------------|
| `/api/v1/networks/{networkId}/memberships` | Grant, read and terminate memberships |
| `/api/v1/networks/{networkId}/identities`  | Members of a network                  |
| `/api/v1/principal`                        | The principal of the current caller   |

## Storage

Tables live in the `membership` schema, created by the Flyway migrations in
`src/main/resources/db/migration/membership`.

##

**<p align="center">[Top](#nexusphere-core-membership)</p>**
