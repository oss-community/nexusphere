# <p align="center">Nexusphere Core Shared</p>

<p align="center">The few primitives every core module speaks in.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)

## Purpose

<p style="text-align: justify;">

Modules must not reach into each other's internals, yet they still need a common vocabulary: the same kind of
identifier, the same execution context, the same event envelope and the same error categories. This module holds exactly
that vocabulary and nothing else, so it can be shared without coupling modules through business concepts.

</p>

## Responsibilities

* Typed identifiers: `NetworkId`, `OrganizationId`, `IdentityId`, `PrincipalId`, `CapabilityId`
* The execution context of a request: `Caller`, `CorrelationId`, `ExecutionContext`
* The domain event contract: `DomainEvent`, `EventEnvelope`, `DomainEventPublisher`
* The error model: `DomainException` with `ValidationException`, `NotFoundException` and `ConflictException`
* `AggregateRoot`, `ResourceReference` and `TimeProvider`
* No business concepts and no framework code; adding one here is a design smell

## Dependencies

| Depends on | Used by                                                                                                                                                                                               |
|------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| none       | `agreement`, `audit`, `authorization`, `bootstrap`, `capability`, `delegation`, `discovery`, `federation`, `identity`, `integration`, `membership`, `network`, `organization`, `transaction`, `trust` |

##

**<p align="center">[Top](#nexusphere-core-shared)</p>**
