# Nexusphere

Federated trust and coordination infrastructure for human and autonomous actors.
The core is a Spring Boot modular monolith: one application (`core/bootstrap`) and one Maven
module per bounded context.

## Prerequisites

* Java 21
* Maven 3.9 or higher
* Docker (for PostgreSQL locally and for Testcontainers in the tests)

## Layout

| Path | Contents |
|---|---|
| `pom.xml` | Technology-neutral root: version, Java release, enforcer, plugin versions |
| `core/pom.xml` | Spring Boot and Spring Modulith BOMs, the list of core modules |
| `core/shared` | Cross-module primitives only: IDs, execution context, event envelope, time, error model |
| `core/network` … `core/integration` | One library module per bounded context, package `com.nexusphere.<module>` |
| `core/bootstrap` | The only Spring Boot application, `com.nexusphere.NexusphereApplication` |
| `core/e2e-tests` | Black-box HTTP tests against the running application and PostgreSQL |
| `infrastructure/postgres/init` | Database init script used by `compose.yaml` |
| `compose.yaml`, `kube-dev.yaml`, `Dockerfile` | Development environment with Docker Compose or Kubernetes |

Inside a module, other modules may only use its `contract` package. Each module owns its
PostgreSQL schema and its migrations under `src/main/resources/db/migration/<module>`.

## Build and test

```shell
mvn verify
```

This runs unit tests, the architecture tests (Spring Modulith and ArchUnit), the integration
test and the end-to-end tests. The last two start PostgreSQL 18 with Testcontainers, so Docker
must be running. To build without tests:

```shell
mvn -DskipTests package
```

## Run locally

Start PostgreSQL, pgAdmin and Adminer, then run the application from Maven:

```shell
docker compose up -d postgresql pgadmin adminer
mvn -DskipTests install
mvn -pl core/bootstrap spring-boot:run
```

Or build the jar and run everything, the application included, with Docker Compose:

```shell
mvn -DskipTests package
docker compose up -d --build
```

To run on Kubernetes in the `dev` namespace:

```shell
mvn -DskipTests package
docker build -t samanalishiri/nexusphere:latest .
kubectl apply -f kube-dev.yaml
```

| Environment variable | Default |
|---|---|
| `APP_HOST` | `0.0.0.0` |
| `APP_PORT` | `8080` |
| `APP_PROFILES` | `postgresql` |
| `APP_DATABASE_HOST` | `localhost` |
| `APP_DATABASE_PORT` | `5432` |
| `APP_DATABASE_DB` | `nexusphere` |
| `APP_DATABASE_USERNAME` | `nexusphere` |
| `APP_DATABASE_PASSWORD` | `nexusphere` |

Profiles: `postgresql` for the database connection, `json` for structured (ECS) logs, for example `APP_PROFILES=postgresql,json`.

| URL | What |
|---|---|
| http://localhost:8080/api/v1/platform | Platform name, version and modules |
| http://localhost:8080/actuator/health | Health |
| http://localhost:8080/swagger-ui.html | OpenAPI UI (`/v3/api-docs` for JSON) |
| http://localhost:8081 | pgAdmin |
| http://localhost:8082 | Adminer |

Every response carries an `X-Correlation-Id` header; send your own to trace a request.
