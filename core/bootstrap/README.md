# <p align="center">Nexusphere Core Bootstrap</p>

<p align="center">The one Spring Boot application that runs the whole core.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)

## Purpose

<p style="text-align: justify;">

The core is a modular monolith: many modules, one deployable. This module assembles them into a single application and
owns everything that is about running it rather than about the domain: security, the web layer, persistence wiring and
events.

</p>

## Responsibilities

* Start `NexusphereApplication` with every core module
* Run each module's Flyway migrations in its own schema before JPA starts
* Authenticate requests with bearer tokens issued for credentials or for the operator, and refuse the development
  secrets outside the dev profile
* Resolve the caller, principal and execution context of each request, and add a correlation ID
* Publish domain events in process, translate errors into one API error format, and serve the OpenAPI document

## Dependencies

| Depends on                                                                                                                                                                                         | Used by |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|
| `shared`, `network`, `organization`, `identity`, `membership`, `authorization`, `trust`, `federation`, `delegation`, `capability`, `discovery`, `agreement`, `transaction`, `audit`, `integration` | none    |

## API

| Path               | Purpose                                                  |
|--------------------|----------------------------------------------------------|
| `/api/v1/auth`     | Exchange a credential or the operator secret for a token |
| `/api/v1/platform` | Platform information                                     |

##

**<p align="center">[Top](#nexusphere-core-bootstrap)</p>**
