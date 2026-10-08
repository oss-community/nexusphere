# <p align="center">Nexusphere Core Discovery</p>

<p align="center">Finding who can do something, inside a network or across federations.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)

## Purpose

<p style="text-align: justify;">

Discovery answers the question "which trusted actor can do X?" while respecting visibility, membership, federation scope
and authorization. It sits behind a port, so the directory-based search of V1 can later be replaced by an index or a
remote registry without touching its callers.

</p>

## Responsibilities

* Search capabilities with criteria and a scope: LOCAL, FEDERATED or ALL
* Reach partner networks only through active federations, with each partner authorized separately
* List the networks a principal can discover in
* Expose `CapabilityDiscovery` to agreements and the agent gateway
* Owns no tables; it reads from the capability and federation modules

## Dependencies

| Depends on                                                                     | Used by                                 |
|--------------------------------------------------------------------------------|-----------------------------------------|
| `shared`, `capability`, `federation`, `network`, `membership`, `authorization` | `agreement`, `bootstrap`, `integration` |

## API

| Path                                     | Purpose                                      |
|------------------------------------------|----------------------------------------------|
| `/api/v1/networks/{networkId}/discovery` | Discover capabilities and reachable networks |

##

**<p align="center">[Top](#nexusphere-core-discovery)</p>**
