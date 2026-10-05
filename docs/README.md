# <p align="center">Nexusphere Project Description</p>

<p align="center">Federated trust and coordination infrastructure for human and autonomous actors.</p>

## <p align="center">Table of Content</p>

* [Vision](#vision)
* [Core Concepts](#core-concepts)
* [Invariants](#invariants)
* [Actors](#actors)
* [Architecture](#architecture)
* [Use Cases](#use-cases)
* [Scenarios](#scenarios)
* [Roadmap](#roadmap)
* [Out of Scope](#out-of-scope)

## Vision

<p style="text-align: justify;">

Nexusphere lets independent networks of companies, organizations, governments, communities and individuals work
together without giving up their sovereignty. Humans, AI agents, applications and machines act inside these networks
as accountable principals. The core answers five questions for every interaction: who is acting, for whom, with what
authority, inside which relationship, and what evidence remains afterwards.

</p>

Nexusphere is a trust core, not a marketplace. Commerce, procurement, banking, messaging, projects, robotics and AI
features are application domains built on top of the core. Agent protocols such as A2A and MCP are not reimplemented;
they enter through adapters, and Nexusphere decides trust, authority and accountability.

## Core Concepts

The core is built on eight pillars.

| Pillar                       | Question it answers                                    | Concepts                                    |
|------------------------------|--------------------------------------------------------|---------------------------------------------|
| Identity                     | Who or what exists?                                    | Identity, identity type, credential         |
| Network sovereignty          | Who governs which space?                               | Network, organization                       |
| Agent and machine identity   | Which autonomous actor is acting, owned by whom?       | Agent identity, machine identity, ownership |
| Capability discovery         | Which trusted actor can do X?                          | Capability type, capability, visibility     |
| Trust and federation         | Which networks may work together, and for what?        | Trust, federation, scope                    |
| Authorization and delegation | Is this principal allowed to do this, on whose behalf? | Role, authorization decision, delegation    |
| Agreements and transactions  | What was agreed and what was executed?                 | Agreement, version, transaction             |
| Audit                        | What happened, and who is accountable?                 | Audit event, accountability chain           |

| Concept       | Meaning                                                                                                                  |
|---------------|--------------------------------------------------------------------------------------------------------------------------|
| Network       | A sovereign space with its own administration, identities, data and policy. States: PENDING, ACTIVE, SUSPENDED, ARCHIVED |
| Organization  | A participant registered inside one network. No department or team hierarchy is forced                                   |
| Identity      | Something that exists: HUMAN, SERVICE, APPLICATION, AGENT or MACHINE. An identity has no authority by itself             |
| Ownership     | Agents and machines are owned by an organization in a network, which is accountable for them                             |
| Credential    | A secret bound to an identity and exchanged for a bearer token. The authentication method is replaceable                 |
| Membership    | Where an identity participates: one active membership per identity and network, as MEMBER or ADMINISTRATOR               |
| Principal     | An identity acting through a membership inside a network. Authorization works on the principal, not on the identity      |
| Capability    | What an organization, agent, machine, service or network can do, described by a typed and versioned specification        |
| Trust         | A scoped, directional and revocable statement of one network about another. Trust does not grant permission              |
| Federation    | An operational relationship between two networks with an agreed scope. Networks stay separate                            |
| Authorization | A central decision (ALLOW or DENY with a reason) that combines roles, ownership, network, federation and delegation      |
| Delegation    | A limited, time-bounded and revocable grant of actions from a principal to another, with a maximum depth of one          |
| Agreement     | A versioned multi-party commitment where the acting principal and the accountable party are recorded separately          |
| Transaction   | An accountable execution under an agreement, not necessarily a payment                                                   |
| Audit event   | An append-only record of every high-impact action, allowed or denied, with its authorization decision                    |

## Invariants

| ID   | Invariant                                                                                     |
|------|-----------------------------------------------------------------------------------------------|
| I-1  | An identity has no authority by itself                                                        |
| I-2  | A membership does not create trust                                                            |
| I-3  | A federation does not merge networks; identity, data, administration and policy stay separate |
| I-4  | Trust does not grant permission                                                               |
| I-5  | Permission does not create delegation                                                         |
| I-6  | A delegation cannot exceed the delegator's authority; the maximum depth in V1 is one          |
| I-7  | Delegations are revocable and time-bounded                                                    |
| I-8  | An agent always acts for an accountable party                                                 |
| I-9  | A machine action is always attributable                                                       |
| I-10 | Agreements keep their full version history; nothing is silently overwritten                   |
| I-11 | A transaction always identifies its acting principal                                          |
| I-12 | Cross-network operations require a valid federation context                                   |
| I-13 | Every high-impact operation, allowed or denied, is audited with its authorization decision    |
| I-14 | The default is DENY; a network ID supplied by a client is never trusted on its own            |

## Actors

| Actor                  | Identity type          | Role                                                    |
|------------------------|------------------------|---------------------------------------------------------|
| Platform operator      | HUMAN                  | Creates networks in V1                                  |
| Network administrator  | HUMAN                  | Administers one network                                 |
| Human member           | HUMAN                  | Acts inside a network through an active membership      |
| AI agent               | AGENT                  | Owned by an organization; acts only under delegation    |
| Machine                | MACHINE                | Owned by an organization; simulated by an adapter in V1 |
| Application or service | APPLICATION or SERVICE | An external system acting as a principal                |
| Counterparty           | any                    | Lives in another sovereign network                      |
| Auditor                | HUMAN                  | Reads the audit trail                                   |

## Architecture

<p style="text-align: justify;">

The core is a Spring Boot modular monolith built with Maven. `core/bootstrap` is the only application; every bounded
context is a library module with the package `com.nexusphere.<module>`. One deployment hosts many sovereign networks;
sovereignty is logical and enforced by the network context of every request.

</p>

### Stack

* Java 21, Spring Boot 4.1, Spring Modulith 2.1, Maven
* PostgreSQL 18 with one schema per module and Flyway migrations per module
* Spring Data JPA, Spring Security resource server, springdoc OpenAPI
* JUnit 5, AssertJ, Testcontainers, ArchUnit
* No Kafka, Redis or Keycloak in V1; ports exist so they can be added later

### Modules

| Module               | Responsibility                                                                    | Depends on                                                 |
|----------------------|-----------------------------------------------------------------------------------|------------------------------------------------------------|
| `core/shared`        | Identifiers, execution context, correlation ID, event envelope, time, error model | nothing                                                    |
| `core/network`       | Network lifecycle                                                                 | shared                                                     |
| `core/organization`  | Organizations inside a network                                                    | network                                                    |
| `core/identity`      | Identities, ownership, credentials                                                | network, organization                                      |
| `core/membership`    | Memberships and principal resolution                                              | identity, organization, network                            |
| `core/authorization` | Roles and central authorization decisions; defines evidence ports                 | membership, identity, network                              |
| `core/trust`         | Scoped, directional trust from networks and organizations                         | identity, membership, network, organization                |
| `core/federation`    | Federation lifecycle and scope                                                    | membership, network, trust                                 |
| `core/delegation`    | Delegations and their constraints                                                 | authorization, membership, network                         |
| `core/capability`    | Capability types and capabilities                                                 | authorization, identity, membership, network, organization |
| `core/discovery`     | Local and federated capability discovery                                          | capability, federation, network, authorization             |
| `core/agreement`     | Versioned agreements                                                              | authorization, capability, federation                      |
| `core/transaction`   | Transactions under agreements                                                     | agreement, authorization, capability                       |
| `core/audit`         | Append-only audit events and accountability trails                                | shared (listens to domain events)                          |
| `core/integration`   | Agent and machine adapters                                                        | identity, capability, discovery, agreement, transaction    |
| `core/bootstrap`     | Application, security, persistence wiring, error handling, architecture tests     | all modules                                                |
| `core/e2e-tests`     | Black-box end-to-end tests, see [End-to-End Tests](../core/e2e-tests/README.md)   | bootstrap at test time                                     |

### Inside a Module

```text
com.nexusphere.<module>
├── contract         the only package other modules may use: directories, snapshots, events
├── domain
│   ├── model        aggregates and value objects, no framework code
│   └── repository   repository ports
├── application      use cases and transactions
├── api/rest         REST controllers and request and response records
└── infrastructure
    └── persistence  JPA entities, Spring Data repositories and adapters
```

### Rules

* Other modules reach a module only through its `contract` package; Spring Modulith and ArchUnit verify this.
* The domain has no Spring, JPA or Jackson dependency; the application layer does not depend on adapters.
* Each module owns its schema and migrations under `db/migration/<module>`; there are no cross-schema foreign keys.
* Aggregates with concurrent writers use optimistic locking.
* Authorization never imports trust, federation or delegation; it defines ports that those modules implement.
* Every request carries a correlation ID, and every error uses one error model.
* Authentication is a replaceable port: V1 issues local HS256 tokens, production can use any OIDC provider.
* A principal context is resolved from the token and the network of the path or the `X-Network-Id` header, and is
  denied without an active membership in that network.

## Use Cases

Status: ✓ implemented, ○ planned.

| ID        | Use case                                                                 | Context       | Phase | Status |
|-----------|--------------------------------------------------------------------------|---------------|-------|--------|
| UC-NET-01 | Create network                                                           | Network       | 2     | ✓      |
| UC-NET-02 | Activate, suspend or archive network                                     | Network       | 2     | ✓      |
| UC-ORG-01 | Register organization in a network                                       | Organization  | 2     | ✓      |
| UC-ORG-02 | Rename or deactivate organization                                        | Organization  | 2     | ✓      |
| UC-IDN-01 | Create human identity                                                    | Identity      | 3     | ✓      |
| UC-IDN-02 | Create agent identity owned by an organization                           | Identity      | 3     | ✓      |
| UC-IDN-03 | Create machine identity                                                  | Identity      | 3     | ✓      |
| UC-IDN-04 | Activate or suspend identity                                             | Identity      | 3     | ✓      |
| UC-IDN-05 | Bind an authentication credential to an identity                         | Identity      | 3     | ✓      |
| UC-PRN-01 | Resolve principal context                                                | Membership    | 3     | ✓      |
| UC-MEM-01 | Activate membership                                                      | Membership    | 3     | ✓      |
| UC-MEM-02 | Terminate membership                                                     | Membership    | 3     | ✓      |
| UC-CAP-01 | Register capability type with schema and version                         | Capability    | 4     | ✓      |
| UC-CAP-02 | Register capability for an owner                                         | Capability    | 4     | ✓      |
| UC-CAP-03 | Publish or withdraw capability and set its visibility                    | Capability    | 4     | ✓      |
| UC-TRU-01 | Establish scoped, directional trust                                      | Trust         | 5     | ✓      |
| UC-TRU-02 | Revoke trust; trust expiry                                               | Trust         | 5     | ✓      |
| UC-FED-01 | Propose federation with scope                                            | Federation    | 5     | ✓      |
| UC-FED-02 | Accept or reject federation                                              | Federation    | 5     | ✓      |
| UC-FED-03 | Suspend, resume or terminate federation                                  | Federation    | 5     | ✓      |
| UC-AUZ-01 | Assign role to a member                                                  | Authorization | 6     | ✓      |
| UC-AUZ-02 | Evaluate an authorization request and return ALLOW or DENY with a reason | Authorization | 6     | ✓      |
| UC-AUZ-03 | Record the authorization decision as evidence                            | Authorization | 6     | ✓      |
| UC-DEL-01 | Grant delegation with action, capability, network and time constraints   | Delegation    | 7     | ✓      |
| UC-DEL-02 | Reject an invalid delegation                                             | Delegation    | 7     | ✓      |
| UC-DEL-03 | Revoke or suspend delegation                                             | Delegation    | 7     | ✓      |
| UC-DEL-04 | Expire delegation                                                        | Delegation    | 7     | ✓      |
| UC-DEL-05 | Get the effective delegations of a principal                             | Delegation    | 7     | ✓      |
| UC-DIS-01 | Discover capabilities locally                                            | Discovery     | 8     | ✓      |
| UC-DIS-02 | Discover capabilities across federated networks                          | Discovery     | 8     | ✓      |
| UC-DIS-03 | Discover networks open to federation                                     | Discovery     | 8     | ✓      |
| UC-AGR-01 | Create agreement draft                                                   | Agreement     | 9     | ○      |
| UC-AGR-02 | Propose an agreement version on behalf of an accountable party           | Agreement     | 9     | ○      |
| UC-AGR-03 | Accept or reject an agreement version                                    | Agreement     | 9     | ○      |
| UC-AGR-04 | Activate, complete or terminate agreement                                | Agreement     | 9     | ○      |
| UC-AGR-05 | Revise agreement, keeping the previous version                           | Agreement     | 9     | ○      |
| UC-TRX-01 | Request transaction                                                      | Transaction   | 10    | ○      |
| UC-TRX-02 | Authorize or reject transaction                                          | Transaction   | 10    | ○      |
| UC-TRX-03 | Execute, complete, fail or cancel transaction                            | Transaction   | 10    | ○      |
| UC-AUD-01 | Record an append-only audit event                                        | Audit         | 11    | ○      |
| UC-AUD-02 | Query the audit trail                                                    | Audit         | 11    | ○      |
| UC-AUD-03 | Reconstruct the accountability chain of a transaction                    | Audit         | 11    | ○      |
| UC-INT-01 | An external agent acts as a principal through the API                    | Integration   | 12    | ○      |
| UC-INT-02 | A simulated machine executes an authorized transaction                   | Integration   | 13    | ○      |

## Scenarios

Every scenario is verified by end-to-end tests listed in [End-to-End Tests](../core/e2e-tests/README.md).

### Acceptance Scenarios

| ID    | Scenario                                      | Summary                                                                                                                                                                                                                       |
|-------|-----------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| SC-01 | Trusted agent interaction across two networks | Human A delegates to Agent A; Agent A discovers a capability of Organization B through an active federation, proposes an agreement, Human B accepts, and an authorized transaction completes with a full accountability chain |
| SC-02 | Machine actor                                 | A machine owned by Organization B executes an authorized transport transaction; the audit attributes it to the machine and its owner                                                                                          |
| SC-03 | Out-of-scope agent action                     | An agent delegated only to propose agreements tries to start a transaction and is denied with `DELEGATION_SCOPE_VIOLATION`                                                                                                    |
| SC-04 | Discovery without federation                  | A capability of a network without trust and federation is never found and is indistinguishable from a missing one                                                                                                             |
| SC-05 | Delegation revocation and expiry              | A revoked or expired delegation denies the very next request while the earlier audit history stays unchanged                                                                                                                  |

#### SC-01 Main Flow

1. Network A is created and activated, and Organization A is registered.
2. Human A gets an identity, a membership in Organization A and a role allowing `agreement:propose` and `transaction:initiate`.
3. Agent A is created as an AGENT owned by Organization A, with an active membership.
4. Human A delegates `capability:discover`, `agreement:propose` and `transaction:initiate` to Agent A for capability
   `manufacturing.cnc`, towards Network B, for thirty days.
5. Network B is created and activated, Organization B is registered, and Human B becomes its administrator.
6. Organization B publishes capability `manufacturing.cnc` with FEDERATED visibility.
7. Network A finds Network B in the directory.
8. Network B trusts Network A for discovery, proposals and transactions; Network A trusts Network B for acceptance.
9. Network A proposes a federation for capability discovery, agreements and transactions; Network B accepts it.
10. Agent A searches for `manufacturing.cnc` and finds Organization B's capability.
11. Authorization evaluates identity, membership, delegation, federation, capability and action, and returns ALLOW.
12. Agent A proposes agreement version 1 on behalf of Organization A.
13. Human B accepts version 1 and the agreement becomes ACTIVE.
14. Agent A requests a `capability.invocation` transaction under the agreement.
15. The transaction is authorized, executed and completed.
16. The accountability chain shows Human A, delegation, Agent A, federation, capability, agreement version 1 and
    transaction, each with the authorization decision that allowed it.

### Building-Block Scenarios

| ID    | Scenario                               | Phase | Status |
|-------|----------------------------------------|-------|--------|
| SC-06 | Sovereign network bootstrap            | 2     | ✓      |
| SC-07 | Human, agent and machine identities    | 3     | ✓      |
| SC-08 | Capability registration and visibility | 4     | ✓      |
| SC-09 | Trust and federation lifecycle         | 5     | ✓      |
| SC-10 | Centralized authorization decisions    | 6     | ✓      |
| SC-11 | Delegation rules                       | 7     | ✓      |
| SC-12 | Governed discovery                     | 8     | ✓      |
| SC-13 | Agreement lifecycle and integrity      | 9     | ○      |
| SC-14 | Transaction lifecycle                  | 10    | ○      |
| SC-15 | Network isolation and context spoofing | 2–15  | ◐      |
| SC-16 | Accountability reconstruction          | 11    | ○      |
| SC-17 | Agent adapter                          | 12    | ○      |

## Roadmap

| Phase | Name                 | Delivers                                                                     | Status |
|-------|----------------------|------------------------------------------------------------------------------|--------|
| 1     | Bootstrap            | Modular monolith, PostgreSQL, Flyway per module, error model, correlation ID | ✓      |
| 2     | Sovereignty          | Networks and organizations with isolation                                    | ✓      |
| 3     | Identity             | Human, agent and machine identities, memberships, principal context, tokens  | ✓      |
| 4     | Capability           | Capability types, capabilities and visibility                                | ✓      |
| 5     | Trust and federation | Directional trust and federation lifecycle                                   | ✓      |
| 6     | Authorization        | Roles and central authorization decisions                                    | ✓      |
| 7     | Delegation           | Constrained, revocable delegations                                           | ✓      |
| 8     | Discovery            | Local and federated discovery                                                | ✓      |
| 9     | Agreement            | Versioned agreements                                                         | ○      |
| 10    | Transaction          | Transactions under agreements                                                | ○      |
| 11    | Audit                | Audit trail and accountability reconstruction                                | ○      |
| 12    | Agent adapter        | External agents acting as principals                                         | ○      |
| 13    | Machine adapter      | Simulated machine executing authorized transactions                          | ○      |

V1 is done when two independent networks, at least two organizations, human, agent and machine identities, memberships,
capability discovery, an active federation, a limited delegation, authorization decisions, an accepted agreement, an
authorized and a denied transaction, a delegation revocation and a complete audit reconstruction all work through the
public API.

## Out of Scope

* Workflow engines, policy languages, templates and module toggles in the core
* Marketplace, RFQ, tender, invoice and payment; they belong to the commerce application
* Messaging, meetings and projects; they belong to the collaboration application
* Synchronisation and outage recovery between deployments
* Real robot control; V1 only simulates a machine
* Reimplementing agent protocols; A2A and MCP are integrated through adapters

##

**<p align="center">[Top](#nexusphere-project-description)</p>**
