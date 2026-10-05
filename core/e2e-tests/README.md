# <p align="center">Nexusphere End-to-End Tests</p>

## <p align="center">Table of Content</p>

* [Getting Started](#getting-started)
* [Test Harness](#test-harness)
* [Test Classes](#test-classes)
* [Scenario Catalog](#scenario-catalog)
* [Cross-Cutting Checks](#cross-cutting-checks)

## Getting Started

### Prerequisites

* [Java 21](https://www.oracle.com/java/technologies/downloads)
* [Maven 3](https://maven.apache.org/index.html)
* [Docker](https://www.docker.com)

### Run All End-to-End Tests

```shell
mvn verify
```

### Run Only This Module

```shell
mvn install -DskipTests=true
mvn -pl core/e2e-tests verify
```

### Run One Test Class

```shell
mvn -pl core/e2e-tests verify -Dit.test=IdentityMembershipE2ETest
```

## Test Harness

<p style="text-align: justify;">

Every test is black-box. It starts the whole application on a random port with a real PostgreSQL 18 from
Testcontainers and talks only to the public REST API, as a human UI, an agent or a machine adapter would. Domain,
repository and architecture tests live in their own modules and are not listed here.

</p>

| Concern        | Decision                                                                                                                                                                                                |
|----------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Runtime        | One application hosts many networks; each test creates its own networks with unique names                                                                                                               |
| Database       | PostgreSQL 18 through Testcontainers; no in-memory database                                                                                                                                             |
| Client         | `ApiClient` sends JSON requests with optional `Authorization`, `X-Network-Id` and `X-Correlation-Id`                                                                                                    |
| Fixtures       | `SovereigntyApi` for networks and organizations, `IdentityApi` for identities, memberships and tokens, `CapabilityApi` for capability types and capabilities, `FederationApi` for trust and federations |
| Authentication | A test creates an identity, issues a credential and exchanges it for a bearer token at `/api/v1/auth/token`                                                                                             |
| Naming         | Test classes end with `E2ETest` and every test is named after its catalog ID                                                                                                                            |

## Test Classes

| Class                              | Package                            | Covers                                                                  |
|------------------------------------|------------------------------------|-------------------------------------------------------------------------|
| `PlatformBootstrapE2ETest`         | `com.nexusphere.e2e.platform`      | E2E-PLT-01..07                                                          |
| `SovereignNetworkBootstrapE2ETest` | `com.nexusphere.e2e.sovereignty`   | E2E-SC06-01..08                                                         |
| `NetworkIsolationE2ETest`          | `com.nexusphere.e2e.sovereignty`   | E2E-SC15-02                                                             |
| `IdentityMembershipE2ETest`        | `com.nexusphere.e2e.identity`      | E2E-SC07-01..06                                                         |
| `IdentityIsolationE2ETest`         | `com.nexusphere.e2e.identity`      | E2E-SC15-01, E2E-SC15-10                                                |
| `CapabilityRegistrationE2ETest`    | `com.nexusphere.e2e.capability`    | E2E-SC08-01..04                                                         |
| `TrustFederationE2ETest`           | `com.nexusphere.e2e.federation`    | E2E-SC09-01..06                                                         |
| `FederationContextE2ETest`         | `com.nexusphere.e2e.federation`    | E2E-SC15-04                                                             |
| `AuthorizationE2ETest`             | `com.nexusphere.e2e.authorization` | E2E-SC10-01..05                                                         |
| `DelegationE2ETest`                | `com.nexusphere.e2e.delegation`    | E2E-SC11-01..05, E2E-SC05-01, 03, E2E-SC15-05                           |
| `DiscoveryE2ETest`                 | `com.nexusphere.e2e.discovery`     | E2E-SC12-01..03, E2E-SC04-01..03, E2E-SC08-03, 04, E2E-SC09-04          |
| `AgreementE2ETest`                 | `com.nexusphere.e2e.agreement`     | E2E-SC13-01..06, E2E-SC05-01, 03, E2E-SC08-04, E2E-SC11-03              |
| `TransactionE2ETest`               | `com.nexusphere.e2e.transaction`   | E2E-SC14-01..04, E2E-SC03-01, E2E-SC09-04                               |
| `PrimaryTrustedInteractionE2ETest` | `com.nexusphere.e2e.primary`       | E2E-SC01-01..05                                                         |
| `AuditE2ETest`                     | `com.nexusphere.e2e.audit`         | E2E-SC16-01..03, E2E-SC03-02, E2E-SC05-02, E2E-SC14-01, E2E-SC15-02, 10 |
| `AgentAdapterE2ETest`              | `com.nexusphere.e2e.agent`         | E2E-SC17-01..03                                                         |
| `MachineAdapterE2ETest`            | `com.nexusphere.e2e.machine`       | E2E-SC02-01..04                                                         |

## Scenario Catalog

Status: ✓ implemented, ◐ partly implemented, ○ planned. The scenarios are described in the
[Project Description](../../docs/README.md#scenarios).

### Platform

| ID         | Test                                                                 | Status |
|------------|----------------------------------------------------------------------|--------|
| E2E-PLT-01 | Health is UP                                                         | ✓      |
| E2E-PLT-02 | The OpenAPI contract is published                                    | ✓      |
| E2E-PLT-03 | A well-formed client correlation ID is echoed                        | ✓      |
| E2E-PLT-04 | A missing or malformed correlation ID is replaced by a generated one | ✓      |
| E2E-PLT-05 | An unknown path answers with the error model and no internals        | ✓      |
| E2E-PLT-06 | An unsupported method answers 405 with the error model               | ✓      |
| E2E-PLT-07 | Platform information lists the schema-owning modules                 | ✓      |

### SC-01 Trusted Agent Interaction Across Two Networks

| ID          | Test                                                                                                                       | Status |
|-------------|----------------------------------------------------------------------------------------------------------------------------|--------|
| E2E-SC01-01 | The sixteen main-flow steps succeed through the API: federation ACTIVE, agreement version 1 ACTIVE, transaction COMPLETED  | ✓      |
| E2E-SC01-02 | The agreement and the transaction show Agent A as acting principal and Organization A as accountable party                 | ✓      |
| E2E-SC01-03 | Every audit event of steps 11 to 15 references an ALLOW decision with the created delegation and federation                | ✓      |
| E2E-SC01-04 | The accountability trail returns Human A, delegation, Agent A, federation, capability, agreement version 1 and transaction | ✓      |
| E2E-SC01-05 | Network B's members do not include Human A or Agent A, and Network A's members do not include Human B                      | ✓      |

### SC-02 Machine Actor

| ID          | Test                                                                                                            | Status |
|-------------|-----------------------------------------------------------------------------------------------------------------|--------|
| E2E-SC02-01 | A transaction for a machine-owned capability completes and the audit names requester, owner, machine and result | ✓      |
| E2E-SC02-02 | The simulator reports FAILED; the transaction is FAILED with a reason and the failure is audited                | ✓      |
| E2E-SC02-03 | A rejected transaction never reaches the simulator                                                              | ✓      |
| E2E-SC02-04 | A machine identity without an owning organization cannot be created                                             | ✓      |

### SC-03 Out-of-Scope Agent Action

| ID          | Test                                                                                                 | Status |
|-------------|------------------------------------------------------------------------------------------------------|--------|
| E2E-SC03-01 | An agent delegated only `agreement:propose` requests a transaction: 403 `DELEGATION_SCOPE_VIOLATION` | ✓      |
| E2E-SC03-02 | The audit trail has a DENIED event linked to the decision and the checked delegation                 | ✓      |

### SC-04 Discovery Without Federation

| ID          | Test                                                                          | Status |
|-------------|-------------------------------------------------------------------------------|--------|
| E2E-SC04-01 | A FEDERATED capability of a network without trust and federation is not found | ✓      |
| E2E-SC04-02 | Fetching that capability by ID returns 404, identical to a random ID          | ✓      |
| E2E-SC04-03 | Trust without federation still excludes the capability                        | ✓      |

### SC-05 Delegation Revocation and Expiry

| ID          | Test                                                                | Status |
|-------------|---------------------------------------------------------------------|--------|
| E2E-SC05-01 | After revocation the next proposal returns 403 `DELEGATION_REVOKED` | ✓      |
| E2E-SC05-02 | Audit events written before the revocation are unchanged            | ✓      |
| E2E-SC05-03 | After the validity ends the action returns 403 `DELEGATION_EXPIRED` | ✓      |

### SC-06 Sovereign Network Bootstrap

| ID          | Test                                                                                           | Status |
|-------------|------------------------------------------------------------------------------------------------|--------|
| E2E-SC06-01 | Two active networks each list only their own organizations                                     | ✓      |
| E2E-SC06-02 | A network with one organization and one with two are both valid; no hierarchy is required      | ✓      |
| E2E-SC06-03 | Activating an active network or reusing an organization name in one network returns 409        | ✓      |
| E2E-SC06-04 | A suspended network blocks new operations but still answers reads                              | ✓      |
| E2E-SC06-05 | A new network is PENDING and takes no organizations until it is activated                      | ✓      |
| E2E-SC06-06 | An archived network cannot be activated or suspended again                                     | ✓      |
| E2E-SC06-07 | Network names are unique, required and validated; unknown IDs return 404 and malformed IDs 400 | ✓      |
| E2E-SC06-08 | An organization can be renamed and deactivated, and then no longer changes                     | ✓      |

### SC-07 Identities and Membership

| ID          | Test                                                                        | Status |
|-------------|-----------------------------------------------------------------------------|--------|
| E2E-SC07-01 | Human, agent and machine identities are created with their types and owners | ✓      |
| E2E-SC07-02 | One identity gets an independent principal context in each of its networks  | ✓      |
| E2E-SC07-03 | A network context without an active membership returns 403                  | ✓      |
| E2E-SC07-04 | A suspended identity's token is rejected for every action                   | ✓      |
| E2E-SC07-05 | Tokens need a valid credential and principal endpoints need a token         | ✓      |
| E2E-SC07-06 | Memberships follow network, ownership and uniqueness rules                  | ✓      |

### SC-08 Capability Registration and Visibility

| ID          | Test                                                                                         | Status |
|-------------|----------------------------------------------------------------------------------------------|--------|
| E2E-SC08-01 | Capabilities owned by an organization, an agent and a machine are registered and published   | ✓      |
| E2E-SC08-02 | A specification that does not match its capability type schema returns 400                   | ✓      |
| E2E-SC08-03 | PRIVATE is visible to its owner, NETWORK to members and FEDERATED also to federated networks | ✓      |
| E2E-SC08-04 | A withdrawn capability disappears from discovery and cannot be used in a new agreement       | ✓      |

### SC-09 Trust and Federation Lifecycle

| ID          | Test                                                                                                           | Status |
|-------------|----------------------------------------------------------------------------------------------------------------|--------|
| E2E-SC09-01 | Trust from A to B does not make B trusted by A                                                                 | ✓      |
| E2E-SC09-02 | Trust stops applying after its end                                                                             | ✓      |
| E2E-SC09-03 | Federation goes PROPOSED, PENDING_ACCEPTANCE, ACTIVE; rejection is terminal                                    | ✓      |
| E2E-SC09-04 | Suspend and resume by the suspending network, terminate is final; suspension blocks discovery and transactions | ✓      |
| E2E-SC09-05 | Only network administrators can propose or accept a federation                                                 | ✓      |
| E2E-SC09-06 | A second active federation between the same networks or a stale acceptance returns 409                         | ✓      |

### SC-10 Centralized Authorization

| ID          | Test                                                                                     | Status |
|-------------|------------------------------------------------------------------------------------------|--------|
| E2E-SC10-01 | An in-network action granted by a role is ALLOW with reason and matched role             | ✓      |
| E2E-SC10-02 | The same action against another network without federation is DENY `FEDERATION_REQUIRED` | ✓      |
| E2E-SC10-03 | An action outside the federation scope is DENY `FEDERATION_SCOPE_VIOLATION`              | ✓      |
| E2E-SC10-04 | Trust without a role or delegation is DENY                                               | ✓      |
| E2E-SC10-05 | Every evaluation can be read back as an authorization decision                           | ✓      |

### SC-11 Delegation Rules

| ID          | Test                                                                                       | Status |
|-------------|--------------------------------------------------------------------------------------------|--------|
| E2E-SC11-01 | Delegating an action the delegator lacks returns 422 `DELEGATION_EXCEEDS_AUTHORITY`        | ✓      |
| E2E-SC11-02 | Delegating onward returns 422 `DELEGATION_DEPTH_EXCEEDED`                                  | ✓      |
| E2E-SC11-03 | Capability and network constraints narrow what the delegate may do                         | ✓      |
| E2E-SC11-04 | After the delegator loses the role, the delegate is denied with `DELEGATOR_AUTHORITY_LOST` | ✓      |
| E2E-SC11-05 | Effective delegations exclude revoked, suspended and expired ones                          | ✓      |

### SC-12 Governed Discovery

| ID          | Test                                                                                                     | Status |
|-------------|----------------------------------------------------------------------------------------------------------|--------|
| E2E-SC12-01 | Local search returns NETWORK and FEDERATED capabilities, never another member's PRIVATE ones             | ✓      |
| E2E-SC12-02 | Federated search returns Network B's capability with origin and owner only when scope and trust allow it | ✓      |
| E2E-SC12-03 | Filters by capability type and owner type narrow the results                                             | ✓      |

### SC-13 Agreement Lifecycle and Integrity

| ID          | Test                                                                                        | Status |
|-------------|---------------------------------------------------------------------------------------------|--------|
| E2E-SC13-01 | DRAFT, PROPOSED, ACCEPTED, ACTIVE, COMPLETED                                                | ✓      |
| E2E-SC13-02 | A revision creates version 2, version 1 stays readable, and accepting version 1 returns 409 | ✓      |
| E2E-SC13-03 | DRAFT directly to ACTIVE returns 409 `AGREEMENT_INVALID_TRANSITION`                         | ✓      |
| E2E-SC13-04 | A principal outside the agreement cannot accept, revise or terminate it                     | ✓      |
| E2E-SC13-05 | Each version shows proposer, accountable party, delegation, time, changes and acceptor      | ✓      |
| E2E-SC13-06 | Of two concurrent revisions with the same expected version, one returns 409                 | ✓      |

### SC-14 Transaction Lifecycle

| ID          | Test                                                                                       | Status |
|-------------|--------------------------------------------------------------------------------------------|--------|
| E2E-SC14-01 | REQUESTED, AUTHORIZED, EXECUTING, COMPLETED, each transition audited                       | ✓      |
| E2E-SC14-02 | A transaction under an agreement that is not ACTIVE is REJECTED                            | ✓      |
| E2E-SC14-03 | A transaction for a capability outside the agreement is REJECTED                           | ✓      |
| E2E-SC14-04 | Cancelling before execution gives CANCELLED; completion by the requesting side returns 403 | ✓      |

### SC-15 Network Isolation

| ID          | Test                                                                                                                  | Status |
|-------------|-----------------------------------------------------------------------------------------------------------------------|--------|
| E2E-SC15-01 | Network A cannot read network B identities by list or by ID                                                           | ✓      |
| E2E-SC15-02 | Organizations, capabilities, agreements and audit events of B are not found from A                                    | ✓      |
| E2E-SC15-03 | Unauthorized capability discovery, covered by E2E-SC04-01..03                                                         | ✓      |
| E2E-SC15-04 | A foreign federation is 404 from the own network and 403 through a spoofed network context; it never widens discovery | ✓      |
| E2E-SC15-05 | Citing another principal's delegation returns 403                                                                     | ✓      |
| E2E-SC15-06 | Delegation privilege escalation, covered by E2E-SC11-01 and 02                                                        | ✓      |
| E2E-SC15-07 | Revoked and expired delegation usage, covered by E2E-SC05-01 and 03                                                   | ✓      |
| E2E-SC15-08 | Unauthorized agreement modification, covered by E2E-SC13-04                                                           | ✓      |
| E2E-SC15-09 | Unauthorized transaction execution, covered by E2E-SC03-01                                                            | ✓      |
| E2E-SC15-10 | A token of A with the network context of B returns 403 and is audited in A                                            | ✓      |

### SC-16 Accountability Reconstruction

| ID          | Test                                                                                                   | Status |
|-------------|--------------------------------------------------------------------------------------------------------|--------|
| E2E-SC16-01 | The trail of a completed transaction returns every element of the accountability chain in causal order | ✓      |
| E2E-SC16-02 | Denied attempts of the same correlation appear with their decisions                                    | ✓      |
| E2E-SC16-03 | Audit events cannot be changed or deleted through any endpoint                                         | ✓      |

### SC-17 Agent Adapter

| ID          | Test                                                                                                            | Status |
|-------------|-----------------------------------------------------------------------------------------------------------------|--------|
| E2E-SC17-01 | A simulated external agent authenticates with its own credential and completes SC-01 steps 10 to 15             | ✓      |
| E2E-SC17-02 | An out-of-scope request through the adapter is denied by the core with `DELEGATION_SCOPE_VIOLATION` and audited | ✓      |
| E2E-SC17-03 | Protocol errors are JSON-RPC errors; without the agent's own token 401, with a foreign network 403              | ✓      |

## Cross-Cutting Checks

* Every response has an `X-Correlation-Id` header.
* Errors use the error model and never leak stack traces or SQL.
* No endpoint returns data of a network the caller has no membership in or federation with.
* Every 4xx for an authorization reason has a matching DENIED audit event.

##

**<p align="center">[Top](#nexusphere-end-to-end-tests)</p>**
