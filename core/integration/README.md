# <p align="center">Nexusphere Core Integration</p>

<p align="center">Adapters between the core and outside agents and machines.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)

## Purpose

<p style="text-align: justify;">

The core speaks its own model; agents and machines speak protocols. This module translates between them so the domain
modules stay protocol-free: an agent gateway in JSON-RPC with an agent card, and a task gateway for machines. Protocols
such as A2A and MCP are reached through adapters here, not reimplemented in the domain.

</p>

## Responsibilities

* Publish an agent card for a network
* Answer JSON-RPC calls from agents: `capabilities/discover`, `capabilities/get`, `agreements/propose`,
  `agreements/get`, `transactions/request`, `transactions/get`
* Let machines start, complete and fail the tasks assigned to them
* Owns no tables; it calls the discovery, agreement and transaction contracts

## Dependencies

| Depends on                                                      | Used by     |
|-----------------------------------------------------------------|-------------|
| `shared`, `membership`, `discovery`, `agreement`, `transaction` | `bootstrap` |

## API

| Path                                         | Purpose                          |
|----------------------------------------------|----------------------------------|
| `/api/v1/networks/{networkId}/agent`         | Agent card and JSON-RPC endpoint |
| `/api/v1/networks/{networkId}/machine/tasks` | Machine task gateway             |

##

**<p align="center">[Top](#nexusphere-core-integration)</p>**
