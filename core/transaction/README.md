# <p align="center">Nexusphere Core Transaction</p>

<p align="center">Accountable executions under authority or an agreement.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Agreeing is not doing. A transaction records an execution performed under an agreement or a delegated authority, always
naming the acting principal, so every outcome can be traced to who did it and why they were allowed to. A transaction is
not necessarily a payment.

</p>

## Responsibilities

* Request, authorize, execute, complete, fail and cancel transactions (REQUESTED, AUTHORIZED, EXECUTING, COMPLETED,
  REJECTED, FAILED, CANCELLED)
* Require every transaction to name its agreement, and keep the authorization decision, delegation, federation and trust
  it ran under
* Expose `TransactionDirectory` and `TransactionCommands` to integration, and publish `TransactionChanged`

## Dependencies

| Depends on                                                         | Used by                             |
|--------------------------------------------------------------------|-------------------------------------|
| `shared`, `agreement`, `authorization`, `capability`, `membership` | `audit`, `bootstrap`, `integration` |

## API

| Path                                        | Purpose                                                        |
|---------------------------------------------|----------------------------------------------------------------|
| `/api/v1/networks/{networkId}/transactions` | Request, read, execute, complete, fail and cancel transactions |

## Storage

Tables live in the `transaction` schema, created by the Flyway migrations in
`src/main/resources/db/migration/transaction`.

##

**<p align="center">[Top](#nexusphere-core-transaction)</p>**
