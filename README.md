# <p align="center">Nexusphere</p>

<p align="center">Federated trust and coordination infrastructure for human and autonomous actors.</p>

## <p align="center">Table of Content</p>

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

<p style="text-align: justify;">

The core is a Spring Boot modular monolith. `core/bootstrap` is the only application and every bounded context is a
Maven module with the package `com.nexusphere.<module>`. A module is reached by other modules only through its
`contract` package, and it owns its PostgreSQL schema and its Flyway migrations under
`src/main/resources/db/migration/<module>`.

</p>

### Modules

| Module                               | Responsibility                                                                     |
|--------------------------------------|------------------------------------------------------------------------------------|
| `core/shared`                        | Identifiers, execution context, correlation ID, domain event envelope, error model |
| `core/network`                       | Network lifecycle: PENDING, ACTIVE, SUSPENDED, ARCHIVED                            |
| `core/organization`                  | Organizations registered inside a network                                          |
| `core/identity` … `core/integration` | Bounded contexts of the next phases                                                |
| `core/bootstrap`                     | Application, persistence wiring, error handling, architecture tests                |
| `core/e2e-tests`                     | End-to-end tests against the application and PostgreSQL                            |

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

| Method | Path                                                                     | Description                                         |
|--------|--------------------------------------------------------------------------|-----------------------------------------------------|
| GET    | `/api/v1/platform`                                                       | Platform information                                |
| POST   | `/api/v1/networks`                                                       | Create a network                                    |
| GET    | `/api/v1/networks`                                                       | List networks                                       |
| GET    | `/api/v1/networks/{networkId}`                                           | Get a network                                       |
| POST   | `/api/v1/networks/{networkId}/activate`                                  | Activate a network                                  |
| POST   | `/api/v1/networks/{networkId}/suspend`                                   | Suspend a network                                   |
| POST   | `/api/v1/networks/{networkId}/archive`                                   | Archive a network                                   |
| POST   | `/api/v1/networks/{networkId}/organizations`                             | Register an organization                            |
| GET    | `/api/v1/networks/{networkId}/organizations`                             | List organizations                                  |
| GET    | `/api/v1/networks/{networkId}/organizations/{organizationId}`            | Get an organization                                 |
| PUT    | `/api/v1/networks/{networkId}/organizations/{organizationId}`            | Rename an organization                              |
| POST   | `/api/v1/networks/{networkId}/organizations/{organizationId}/deactivate` | Deactivate an organization                          |
| POST   | `/api/v1/identities`                                                     | Create an identity                                  |
| POST   | `/api/v1/identities/{identityId}/suspend`                                | Suspend an identity                                 |
| POST   | `/api/v1/identities/{identityId}/activate`                               | Activate an identity                                |
| POST   | `/api/v1/identities/{identityId}/credentials`                            | Issue a credential secret                           |
| POST   | `/api/v1/auth/token`                                                     | Exchange a credential for a bearer token            |
| POST   | `/api/v1/networks/{networkId}/memberships`                               | Activate a membership                               |
| GET    | `/api/v1/networks/{networkId}/memberships`                               | List memberships                                    |
| GET    | `/api/v1/networks/{networkId}/memberships/{membershipId}`                | Get a membership                                    |
| POST   | `/api/v1/networks/{networkId}/memberships/{membershipId}/terminate`      | Terminate a membership                              |
| GET    | `/api/v1/networks/{networkId}/identities`                                | List member identities (bearer token)               |
| GET    | `/api/v1/networks/{networkId}/identities/{identityId}`                   | Get a member identity (bearer token)                |
| GET    | `/api/v1/principal`                                                      | Principal context for `X-Network-Id` (bearer token) |

Requests marked with bearer token need `Authorization: Bearer <token>` and a network context, taken from the path
or the `X-Network-Id` header. The identity must be active and hold an active membership in that network.

```shell
curl -X POST http://localhost:8080/api/v1/identities -H "Content-Type: application/json" -d '{"type":"HUMAN","displayName":"Alice"}'
curl -X POST http://localhost:8080/api/v1/networks/{networkId}/memberships -H "Content-Type: application/json" -d '{"identityId":"{identityId}"}'
curl -X POST http://localhost:8080/api/v1/identities/{identityId}/credentials
curl -X POST http://localhost:8080/api/v1/auth/token -H "Content-Type: application/json" -d '{"identityId":"{identityId}","secret":"{secret}"}'
curl -X GET http://localhost:8080/api/v1/principal -H "Authorization: Bearer {accessToken}" -H "X-Network-Id: {networkId}"
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
