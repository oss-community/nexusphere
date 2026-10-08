# <p align="center">Nexusphere Core Agreement</p>

<p align="center">Versioned commitments between accountable parties.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Work between parties needs a record of what was agreed, by whom, and on whose behalf. An agreement keeps every version
of its terms, the acting principal and the accountable party separately, and never silently overwrites history.

</p>

## Responsibilities

* Draft, propose, revise, accept, reject, activate, complete and terminate agreements (DRAFT, PROPOSED, ACCEPTED,
  ACTIVE, COMPLETED, REJECTED, TERMINATED)
* Keep the full version history of the terms, with each party's acceptance
* Link agreements to the capabilities they are about
* Expose `AgreementDirectory` and `AgreementCommands` to transactions and integration, and publish `AgreementChanged`

## Dependencies

| Depends on                                                         | Used by                                            |
|--------------------------------------------------------------------|----------------------------------------------------|
| `shared`, `authorization`, `capability`, `discovery`, `membership` | `audit`, `bootstrap`, `integration`, `transaction` |

## API

| Path                                      | Purpose                                                                                  |
|-------------------------------------------|------------------------------------------------------------------------------------------|
| `/api/v1/networks/{networkId}/agreements` | Create, read, propose, revise and move agreements through their lifecycle; read versions |

## Storage

Tables live in the `agreement` schema, created by the Flyway migrations in `src/main/resources/db/migration/agreement`.

##

**<p align="center">[Top](#nexusphere-core-agreement)</p>**
