# <p align="center">Nexusphere Core Authorization</p>

<p align="center">One place that says ALLOW or DENY, with a reason.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Storage](#storage)

## Purpose

<p style="text-align: justify;">

Permission checks scattered across modules drift apart and leave no record. This module makes every decision centrally,
combining roles, ownership, network, federation and delegation, defaults to DENY and stores each decision so the audit
trail can point at it.

</p>

## Responsibilities

* Evaluate an `AuthorizationRequest` through the `Authorizer` contract and return an `AuthorizationDecision` with its
  reason
* Assign and revoke network roles: MEMBER, CAPABILITY_MANAGER, AGREEMENT_MANAGER, TRANSACTION_OPERATOR,
  FEDERATION_MANAGER, AUDITOR, NETWORK_ADMINISTRATOR
* Refuse self role assignment and delegated management, so nobody can raise their own authority
* Ask the delegation, federation and trust modules for evidence through ports, without depending on them
* Record every decision and publish `AuthorizationGranted`, `AuthorizationDenied`, `RoleAssigned` and `RoleRevoked`

## Dependencies

| Depends on                                    | Used by                                                                                                          |
|-----------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `shared`, `membership`, `identity`, `network` | `agreement`, `audit`, `bootstrap`, `capability`, `delegation`, `discovery`, `federation`, `transaction`, `trust` |

## API

| Path                                            | Purpose                                             |
|-------------------------------------------------|-----------------------------------------------------|
| `/api/v1/authorization`                         | Evaluate a request, read a decision, list the roles |
| `/api/v1/networks/{networkId}/role-assignments` | Assign, list and revoke roles                       |

## Storage

Tables live in the `authorization` schema, created by the Flyway migrations in
`src/main/resources/db/migration/authorization`.

##

**<p align="center">[Top](#nexusphere-core-authorization)</p>**
