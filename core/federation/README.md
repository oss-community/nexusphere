# <p align="center">Nexusphere Core Federation</p>

<p align="center">Working relationships between sovereign networks.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Networks need to cooperate without merging. A federation is an agreed relationship between two networks with an explicit
scope, while identities, data, administration and policy stay separate. Every cross-network operation needs an active
federation that covers it.

</p>

## Responsibilities

* Propose, submit, accept, reject, suspend, resume and terminate federations
* Limit each federation to scopes: CAPABILITY_DISCOVERY, CAPABILITY_INVOCATION, IDENTITY_VISIBILITY, AGREEMENT_CREATION,
  TRANSACTION_EXCHANGE
* Give authorization the federation context of a cross-network request through `FederationContextPort`
* Publish an event for every state change

## Dependencies

| Depends on                                                  | Used by                  |
|-------------------------------------------------------------|--------------------------|
| `shared`, `authorization`, `trust`, `network`, `membership` | `bootstrap`, `discovery` |

## API

| Path                                       | Purpose                                                    |
|--------------------------------------------|------------------------------------------------------------|
| `/api/v1/networks/{networkId}/federations` | Propose, read and move federations through their lifecycle |

## Storage

Tables live in the `federation` schema, created by the Flyway migrations in
`src/main/resources/db/migration/federation`.

##

**<p align="center">[Top](#nexusphere-core-federation)</p>**
