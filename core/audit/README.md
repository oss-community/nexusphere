# <p align="center">Nexusphere Core Audit</p>

<p align="center">An append-only trail of who did what, and whether it was allowed.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Accountability needs a record that nobody can quietly change and that is separate from application logs. Every
high-impact action, allowed or denied, is written here with its authorization decision, so an auditor can follow an
accountability chain from an agent back to the party responsible for it.

</p>

## Responsibilities

* Record audit events from the domain events of the other modules through `AuditRecorder`
* Keep events append-only, with results ALLOWED, DENIED, SUCCEEDED, REJECTED and FAILED
* Query the trail by transaction, agreement, delegation, decision, principal, correlation ID, result or resource, and
  the platform trail for operators
* Expose `AuditTrail` to other modules

## Dependencies

| Depends on                                                                                    | Used by     |
|-----------------------------------------------------------------------------------------------|-------------|
| `shared`, `authorization`, `identity`, `membership`, `capability`, `agreement`, `transaction` | `bootstrap` |

## API

| Path                   | Purpose                                                           |
|------------------------|-------------------------------------------------------------------|
| `/api/v1/audit-events` | Read audit events, the trail of a resource and the platform trail |

## Storage

Tables live in the `audit` schema, created by the Flyway migrations in `src/main/resources/db/migration/audit`.

##

**<p align="center">[Top](#nexusphere-core-audit)</p>**
