# <p align="center">Nexusphere</p>

<p align="center">Federated trust and coordination infrastructure for human and autonomous actors.</p>

## <p align="center">Table of Content</p>

* [Project Description](docs/README.md)
* [End-to-End Tests](core/e2e-tests/README.md)
* [Getting Started](#getting-started)
* [Dockerized](#dockerized)
* [Kubernetes](#kubernetes)
* [UI](#ui)
* [Nexusphere Core](#nexusphere-core)

## Getting Started

### Prerequisites

* [Java 21](https://www.oracle.com/java/technologies/downloads)
* [Maven 3](https://maven.apache.org/index.html)
* [Docker](https://www.docker.com)
* [Kubernetes](https://kubernetes.io)

### Build

```shell
mvn validate clean compile
```

### Test

```shell
mvn test
```

### Package

```shell
mvn package -DskipTests=true
```

### Run

```shell
docker compose --file compose.yaml --project-name dev up -d postgresql pgadmin adminer
mvn install -DskipTests=true
mvn -pl core/bootstrap spring-boot:start
```

### E2eTest

```shell
curl -X GET http://localhost:8080/actuator/health
curl -X GET http://localhost:8080/api/v1/platform
```

```shell
curl -X POST http://localhost:8080/api/v1/networks -H "Content-Type: application/json" -d '{"name":"Network A"}'
curl -X POST http://localhost:8080/api/v1/networks/{networkId}/activate
curl -X POST http://localhost:8080/api/v1/networks/{networkId}/organizations -H "Content-Type: application/json" -d '{"name":"Acme"}'
curl -X GET http://localhost:8080/api/v1/networks/{networkId}/organizations
```

### Stop

```shell
mvn -pl core/bootstrap spring-boot:stop
docker compose --file compose.yaml --project-name dev down
```

### Verify

```shell
mvn verify
docker volume prune -f
```

## Dockerized

### Deploy

```shell
mvn clean package verify -DskipTests=true
docker compose --file compose.yaml --project-name dev up --build -d
```

### E2eTest

```shell
curl -X GET http://localhost:8080/actuator/health
curl -X GET http://localhost:8080/api/v1/platform
```

### Down

```shell
docker compose --file compose.yaml --project-name dev down
docker image rm samanalishiri/nexusphere:latest
docker volume prune -f
```

## Kubernetes

### Deploy

```shell
mvn clean package verify -DskipTests=true
docker build -t samanalishiri/nexusphere:latest . --no-cache
kubectl apply -f kube-dev.yaml
```

### Check Status

```shell
kubectl get all -n dev
```

### Port Forwarding

```shell
# PostgreSQL
kubectl port-forward service/postgresql 5432:5432 -n dev
```

```shell
# PgAdmin
kubectl port-forward service/pgadmin 8081:80 -n dev
```

```shell
# Adminer
kubectl port-forward service/adminer 8082:8080 -n dev
```

```shell
# Application
kubectl port-forward service/application 8080:8080 -n dev
```

### E2eTest

```shell
curl -X GET http://localhost:8080/actuator/health
curl -X GET http://localhost:8080/api/v1/platform
```

### Down

```shell
kubectl delete all --all -n dev
kubectl delete secrets dev-credentials -n dev
kubectl delete configMap dev-config -n dev
kubectl delete persistentvolumeclaim postgres-pvc -n dev
kubectl delete persistentvolumeclaim pgadmin-pvc -n dev
docker image rm samanalishiri/nexusphere:latest
docker volume prune -f
```

## UI

* Application: [http://localhost:8080](http://localhost:8080)
* Swagger UI: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
* OpenAPI: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
* Health: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
* PgAdmin: [http://localhost:8081](http://localhost:8081)
* Adminer: [http://localhost:8082](http://localhost:8082)

```yaml
# PgAdmin
Host: postgresql
Port: 5432
Maintenance_database: nexusphere
# Adminer
Server: postgresql:5432

Username: nexusphere
Password: nexusphere
```

---

## Nexusphere Core

The vision, concepts, invariants, architecture, use cases, scenarios and roadmap are described in the
[Project Description](docs/README.md). The end-to-end test scenarios are listed in
[End-to-End Tests](core/e2e-tests/README.md).


<p style="text-align: justify;">

The core is a Spring Boot modular monolith. `core/bootstrap` is the only application and every bounded context is a
Maven module with the package `com.nexusphere.<module>`. A module is reached by other modules only through its
`contract` package, and it owns its PostgreSQL schema and its Flyway migrations under
`src/main/resources/db/migration/<module>`.

</p>

### Modules

| Module                                  | Responsibility                                                                         |
|-----------------------------------------|----------------------------------------------------------------------------------------|
| `core/shared`                           | Identifiers, execution context, correlation ID, domain event envelope, error model     |
| `core/network`                          | Network lifecycle: PENDING, ACTIVE, SUSPENDED, ARCHIVED                                |
| `core/organization`                     | Organizations registered inside a network                                              |
| `core/identity`                         | Human, service, application, agent and machine identities and credentials              |
| `core/membership`                       | Memberships, principal context and member listing                                      |
| `core/capability`                       | Capability types with versioned schemas, capabilities, visibility and withdrawal       |
| `core/trust`                            | Scoped, directional, revocable trust between networks, organizations and identities    |
| `core/federation`                       | Federation lifecycle between two sovereign networks with scope and optimistic locking  |
| `core/authorization`                    | Roles, role assignments, central ALLOW/DENY decisions recorded as evidence             |
| `core/delegation`                       | Constrained, time-bounded, revocable delegations between principals of one network     |
| `core/discovery`                        | Governed local and federated capability discovery behind a port                        |
| `core/agreement`                        | Versioned agreements between accountable parties, with acting principal and delegation |
| `core/transaction` … `core/integration` | Bounded contexts of the next phases                                                    |
| `core/bootstrap`                        | Application, persistence wiring, error handling, architecture tests                    |
| `core/e2e-tests`                        | End-to-end tests against the application and PostgreSQL                                |

### Profiles

| Profile      | Description                     |
|--------------|---------------------------------|
| `postgresql` | PostgreSQL datasource (default) |
| `json`       | Structured (ECS) console logs   |

```shell
APP_PROFILES=postgresql,json mvn -pl core/bootstrap spring-boot:start
```

### Environment Variables

```yaml
APP_HOST: 0.0.0.0
APP_PORT: 8080
APP_PROFILES: postgresql
APP_DATABASE_HOST: localhost
APP_DATABASE_PORT: 5432
APP_DATABASE_DB: nexusphere
APP_DATABASE_USERNAME: nexusphere
APP_DATABASE_PASSWORD: nexusphere
APP_TOKEN_ISSUER: nexusphere
APP_TOKEN_SECRET: nexusphere-development-token-secret-change-me
APP_TOKEN_TTL: 15m
```

### API

| Method | Path                                                                     | Description                                                                                    |
|--------|--------------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| GET    | `/api/v1/platform`                                                       | Platform information                                                                           |
| POST   | `/api/v1/networks`                                                       | Create a network                                                                               |
| GET    | `/api/v1/networks`                                                       | List networks                                                                                  |
| GET    | `/api/v1/networks/{networkId}`                                           | Get a network                                                                                  |
| POST   | `/api/v1/networks/{networkId}/activate`                                  | Activate a network                                                                             |
| POST   | `/api/v1/networks/{networkId}/suspend`                                   | Suspend a network                                                                              |
| POST   | `/api/v1/networks/{networkId}/archive`                                   | Archive a network                                                                              |
| POST   | `/api/v1/networks/{networkId}/organizations`                             | Register an organization                                                                       |
| GET    | `/api/v1/networks/{networkId}/organizations`                             | List organizations                                                                             |
| GET    | `/api/v1/networks/{networkId}/organizations/{organizationId}`            | Get an organization                                                                            |
| PUT    | `/api/v1/networks/{networkId}/organizations/{organizationId}`            | Rename an organization                                                                         |
| POST   | `/api/v1/networks/{networkId}/organizations/{organizationId}/deactivate` | Deactivate an organization                                                                     |
| POST   | `/api/v1/identities`                                                     | Create an identity                                                                             |
| POST   | `/api/v1/identities/{identityId}/suspend`                                | Suspend an identity                                                                            |
| POST   | `/api/v1/identities/{identityId}/activate`                               | Activate an identity                                                                           |
| POST   | `/api/v1/identities/{identityId}/credentials`                            | Issue a credential secret                                                                      |
| POST   | `/api/v1/auth/token`                                                     | Exchange a credential for a bearer token                                                       |
| POST   | `/api/v1/networks/{networkId}/memberships`                               | Activate a membership, optionally as `ADMINISTRATOR`                                           |
| GET    | `/api/v1/networks/{networkId}/memberships`                               | List memberships                                                                               |
| GET    | `/api/v1/networks/{networkId}/memberships/{membershipId}`                | Get a membership                                                                               |
| POST   | `/api/v1/networks/{networkId}/memberships/{membershipId}/terminate`      | Terminate a membership                                                                         |
| GET    | `/api/v1/networks/{networkId}/identities`                                | List member identities (bearer token)                                                          |
| GET    | `/api/v1/networks/{networkId}/identities/{identityId}`                   | Get a member identity (bearer token)                                                           |
| GET    | `/api/v1/principal`                                                      | Principal context for `X-Network-Id` (bearer token)                                            |
| POST   | `/api/v1/capability-types`                                               | Register a capability type or its next version                                                 |
| GET    | `/api/v1/capability-types`                                               | List capability types, optionally by `code`                                                    |
| GET    | `/api/v1/capability-types/{typeId}`                                      | Get a capability type                                                                          |
| POST   | `/api/v1/networks/{networkId}/capabilities`                              | Register a capability (bearer token)                                                           |
| GET    | `/api/v1/networks/{networkId}/capabilities`                              | List visible capabilities (bearer token)                                                       |
| GET    | `/api/v1/networks/{networkId}/capabilities/{capabilityId}`               | Get a visible capability (bearer token)                                                        |
| POST   | `/api/v1/networks/{networkId}/capabilities/{capabilityId}/publish`       | Publish a capability (bearer token)                                                            |
| PUT    | `/api/v1/networks/{networkId}/capabilities/{capabilityId}/visibility`    | Change the visibility (bearer token)                                                           |
| POST   | `/api/v1/networks/{networkId}/capabilities/{capabilityId}/withdraw`      | Withdraw a capability (bearer token)                                                           |
| POST   | `/api/v1/networks/{networkId}/trust-relationships`                       | Establish trust from the network or an organization (bearer token)                             |
| GET    | `/api/v1/networks/{networkId}/trust-relationships`                       | List trust relationships, optionally by `direction` (bearer token)                             |
| GET    | `/api/v1/networks/{networkId}/trust-relationships/{trustId}`             | Get a trust relationship (bearer token)                                                        |
| POST   | `/api/v1/networks/{networkId}/trust-relationships/{trustId}/revoke`      | Revoke a trust relationship (bearer token)                                                     |
| GET    | `/api/v1/networks/{networkId}/trust-relationships/evaluation`            | Check whether a source trusts a target for a scope (bearer token)                              |
| POST   | `/api/v1/networks/{networkId}/federations`                               | Propose a federation (administrator)                                                           |
| GET    | `/api/v1/networks/{networkId}/federations`                               | List federations of the network (bearer token)                                                 |
| GET    | `/api/v1/networks/{networkId}/federations/{federationId}`                | Get a federation (bearer token)                                                                |
| POST   | `/api/v1/networks/{networkId}/federations/{federationId}/{action}`       | `submit`, `accept`, `reject`, `suspend`, `resume` or `terminate` (administrator)               |
| GET    | `/api/v1/authorization/roles`                                            | Role catalog with the actions of each role                                                     |
| POST   | `/api/v1/authorization/evaluate`                                         | Evaluate an action and record the decision (bearer token)                                      |
| GET    | `/api/v1/authorization/decisions/{decisionId}`                           | Read a recorded decision (bearer token)                                                        |
| POST   | `/api/v1/networks/{networkId}/role-assignments`                          | Assign a role to a principal (`role:assign`)                                                   |
| GET    | `/api/v1/networks/{networkId}/role-assignments`                          | List role assignments, optionally by `principalId` (bearer token)                              |
| POST   | `/api/v1/networks/{networkId}/role-assignments/{assignmentId}/revoke`    | Revoke a role assignment (`role:assign`)                                                       |
| POST   | `/api/v1/networks/{networkId}/delegations`                               | Grant a delegation with actions, constraints and validity (`delegation:grant`)                 |
| GET    | `/api/v1/networks/{networkId}/delegations`                               | List delegations, by `delegatePrincipalId`, `delegatorPrincipalId`, `effective` (bearer token) |
| GET    | `/api/v1/networks/{networkId}/delegations/{delegationId}`                | Get a delegation with its derived status (bearer token)                                        |
| POST   | `/api/v1/networks/{networkId}/delegations/{delegationId}/{action}`       | `revoke`, `suspend` or `resume` (delegator or administrator)                                   |
| GET    | `/api/v1/networks/{networkId}/discovery/capabilities`                    | Search by `typeCode`, `ownerType`, `organizationId`, `originNetworkId`, `scope` (bearer token) |
| GET    | `/api/v1/networks/{networkId}/discovery/capabilities/{capabilityId}`     | Get a discoverable capability, local or federated (bearer token)                               |
| GET    | `/api/v1/networks/{networkId}/discovery/networks`                        | Other active networks with their federation state (bearer token)                               |
| POST   | `/api/v1/networks/{networkId}/agreements`                                | Draft an agreement for a discovered capability (`agreement:propose`)                           |
| GET    | `/api/v1/networks/{networkId}/agreements`                                | List agreements the principal is party to (bearer token)                                       |
| GET    | `/api/v1/networks/{networkId}/agreements/{agreementId}`                  | Get an agreement with its current version (bearer token)                                       |
| GET    | `/api/v1/networks/{networkId}/agreements/{agreementId}/versions`         | All versions, also `/versions/{number}` (bearer token)                                         |
| POST   | `/api/v1/networks/{networkId}/agreements/{agreementId}/propose`          | Propose the draft (`agreement:propose`)                                                        |
| POST   | `/api/v1/networks/{networkId}/agreements/{agreementId}/revisions`        | Revise with `expectedVersion`, creating the next version (`agreement:propose`)                 |
| POST   | `/api/v1/networks/{networkId}/agreements/{agreementId}/{decision}`       | `accept` or `reject` a version (`agreement:accept`)                                            |
| POST   | `/api/v1/networks/{networkId}/agreements/{agreementId}/{action}`         | `activate`, `complete` or `terminate` (`agreement:manage`)                                     |

Every active member holds the MEMBER role; an `ADMINISTRATOR` membership holds NETWORK_ADMINISTRATOR. Requests that name an
action in parentheses are checked by the central authorizer, which records an ALLOW or DENY decision. Requests marked with bearer token need `Authorization: Bearer <token>` and a network context, taken from the path
or the `X-Network-Id` header. The identity must be active and hold an active membership in that network.

```shell
curl -X POST http://localhost:8080/api/v1/identities -H "Content-Type: application/json" -d '{"type":"HUMAN","displayName":"Alice"}'
curl -X POST http://localhost:8080/api/v1/networks/{networkId}/memberships -H "Content-Type: application/json" -d '{"identityId":"{identityId}"}'
curl -X POST http://localhost:8080/api/v1/identities/{identityId}/credentials
curl -X POST http://localhost:8080/api/v1/auth/token -H "Content-Type: application/json" -d '{"identityId":"{identityId}","secret":"{secret}"}'
curl -X GET http://localhost:8080/api/v1/principal -H "Authorization: Bearer {accessToken}" -H "X-Network-Id: {networkId}"
```

```shell
curl -X POST http://localhost:8080/api/v1/capability-types -H "Content-Type: application/json" -d '{"code":"manufacturing.cnc","name":"CNC machining","schema":{"type":"object","required":["material"],"properties":{"material":{"type":"string"}}}}'
curl -X POST http://localhost:8080/api/v1/networks/{networkId}/capabilities -H "Authorization: Bearer {accessToken}" -H "Content-Type: application/json" -d '{"name":"Precision CNC","typeCode":"manufacturing.cnc","specification":{"material":"steel"}}'
curl -X POST http://localhost:8080/api/v1/networks/{networkId}/capabilities/{capabilityId}/publish -H "Authorization: Bearer {accessToken}" -H "Content-Type: application/json" -d '{"visibility":"NETWORK"}'
curl -X GET http://localhost:8080/api/v1/networks/{networkId}/capabilities -H "Authorization: Bearer {accessToken}"
```

```shell
curl -X POST http://localhost:8080/api/v1/networks/{networkA}/memberships -H "Content-Type: application/json" -d '{"identityId":"{identityId}","role":"ADMINISTRATOR"}'
curl -X POST http://localhost:8080/api/v1/networks/{networkA}/trust-relationships -H "Authorization: Bearer {adminA}" -H "Content-Type: application/json" -d '{"target":{"type":"NETWORK","id":"{networkB}"},"scopes":["capability:discover"]}'
curl -X POST http://localhost:8080/api/v1/networks/{networkA}/federations -H "Authorization: Bearer {adminA}" -H "Content-Type: application/json" -d '{"partnerNetworkId":"{networkB}","scopes":["CAPABILITY_DISCOVERY","AGREEMENT_CREATION"]}'
curl -X POST http://localhost:8080/api/v1/networks/{networkA}/federations/{federationId}/submit -H "Authorization: Bearer {adminA}"
curl -X POST http://localhost:8080/api/v1/networks/{networkB}/federations/{federationId}/accept -H "Authorization: Bearer {adminB}" -H "Content-Type: application/json" -d '{"version":1}'
```

Every response carries an `X-Correlation-Id` header. Errors use one model:

```json
{
  "code": "NETWORK_NOT_ACTIVE",
  "category": "CONFLICT",
  "message": "Network 7c1e… is not active",
  "correlationId": "4f0b…"
}
```

##

**<p align="center">[Top](#nexusphere)</p>**
