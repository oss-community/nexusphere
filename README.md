# <p align="center">Nexusphere</p>

<p align="center">Verifiable evidence of what AI agents do, for whom and with whose permission, across organizations.</p>

## <p align="center">Table of Content</p>

* [Documentation](#documentation)
* [Nexusphere Ledger](#nexusphere-ledger)
* [Quick Start](#quick-start)
* [Build and Test](#build-and-test)
* [Kubernetes](#kubernetes)
* [DevOps](#devops)
* [Service URLs](#service-urls)
* [Repository Layout](#repository-layout)

## Documentation

<p style="text-align: justify;">

Read the documents in this order. The first three take a newcomer from nothing to a verified evidence package; the
rest are references to open when they are needed. All of them are also in one book,
[Nexusphere Guide (PDF)](docs/nexusphere-guide.pdf).

</p>

| Step | Document                                                     | What it gives                                                       |
|------|--------------------------------------------------------------|---------------------------------------------------------------------|
| 1    | This page                                                    | What the ledger is and the quick start                              |
| 2    | [Project Description](docs/README.md)                        | The problem, the concepts, the architecture and the standards       |
| 3    | [Ledger Guide](docs/ledger-guide.md)                         | Install, register agents, grant, call tools and verify step by step |
| 4    | [Nexusphere Ledger](ledger/README.md)                        | Every setting, format and endpoint, and one README per module       |
| 5    | [Nexusphere Frontend](frontend/README.md)                    | The web UI for operators and principals                             |
| 6    | [Operations](docs/operations.md)                             | Secrets, signing keys, backup, monitoring, alerts and rate limits   |
| 7    | [Local Environment Setup](docs/local-setup.md)               | Developer machine settings for Windows, shells and the IDE          |
| 8    | [DevOps Step by Step](docs/devops-guide.md)                  | The build pipelines with mvn-devops                                 |
| 9    | [Ledger End-to-End Tests](ledger/ledger-e2e-tests/README.md) | What the end-to-end tests cover                                     |
| 10   | [Conformance Vectors](ledger/conformance/README.md)          | Test data for another implementation of the formats                 |
| 11   | [Python SDK](ledger/sdk/python/README.md)                    | Record evidence from Python and verify packages and mandates        |
| 12   | [TypeScript SDK](ledger/sdk/typescript/README.md)            | The same for Node and browsers                                      |
| 13   | [OpenTelemetry Exporter](ledger/otel-exporter/README.md)     | Evidence from the agent spans an OpenTelemetry Collector receives   |
| 14   | [Adapters](ledger/adapters/README.md)                        | LangGraph, OpenAI Agents SDK, AgentCore and agentgateway            |
| 15   | [Browser Verifier](ledger/verifier-web/README.md)            | Verify a package in a browser, from one file, with no network       |
| 16   | [Changelog](ledger/CHANGELOG.md)                             | What each release contains                                          |

## Nexusphere Ledger

<p style="text-align: justify;">

The Nexusphere Ledger is a self-hosted evidence layer for AI agents: every action an agent takes for a person or an
organization becomes an entry that a third party can verify offline, without trusting the operator. It does not
replace the agent platforms and gateways that already decide what an agent may do; it sits beside them and keeps the
proof. A gateway or agent platform sends its decisions and outcomes to `POST /api/v1/evidence`, and where no gateway
exists the ledger's own MCP and A2A gateways decide and record.

</p>

| Need                                       | Already provided by                                                                    | What the ledger adds                                                                                           |
|--------------------------------------------|----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| Agent identity and tool-call decisions     | Amazon Bedrock AgentCore, Google Agent Gateway, Microsoft Entra Agent ID, agentgateway | Records their decisions as evidence, or decides itself through its MCP and A2A gateways                        |
| Evidence a third party can verify          | Logs and audit trails the operator controls                                            | Hash chain, Merkle transparency log with witness cosignatures, SCITT statements and receipts, offline verifier |
| Proof of authority across organizations    | AP2 and Verifiable Intent, for payments only                                           | Grants with principal consent, SD-JWT VC mandates and signed A2A receipts on both sides                        |
| Disclosure to an auditor or a counterparty | Exports of raw logs                                                                    | Evidence packages that disclose only the selected entries and prove the rest of the log                        |

<p style="text-align: justify;">

The formats follow open standards so other tools can check them: RFC 9162 Merkle trees, C2SP signed notes and the
`tlog-witness` protocol, IETF SCITT signed statements with RFC 9942 receipts, SD-JWT VCs and the IETF Token Status
List. [Conformance Vectors](ledger/conformance/README.md) let another implementation test itself against the ledger.

</p>

## Quick Start

<p style="text-align: justify;">

These steps start the ledger with its database, Keycloak, the web UI and a demo MCP server, run the demo and stop
everything again. Every command runs in the root of the repository.

</p>

Step 1. Check the tools; each command must print a version:

| Tool   | Version | Check                    |
|--------|---------|--------------------------|
| Java   | 21      | `java -version`          |
| Maven  | 3.9     | `mvn -version`           |
| Docker | any     | `docker compose version` |
| jq     | any     | `jq --version`           |

Windows, shell and IDE settings are in [Local Environment Setup](docs/local-setup.md).

Step 2. Build the server, the verifier and the demo MCP server:

```shell
mvn -pl ledger/server,ledger/verifier,ledger/demo-mcp -am package -DskipTests=true
```

Step 3. Start the services:

```shell
docker compose --file ledger/compose.yaml --project-name ledger up -d --build
```

Step 4. Check that the ledger is up; the answer must be `{"status":"UP"}`:

```shell
curl -X GET http://localhost:8090/actuator/health
```

Step 5. Run the demo; it must end with `Result: VALID` for the package and `Result: INVALID` for the changed copy:

```shell
ledger/demo-mcp/demo.sh
```

Step 6. Open the web UI on http://localhost:5173, sign in with the operator key
`nexusphere-ledger-development-key-change-me`, and look at the evidence the demo recorded. Sign out and sign in again
as `alice` with the password `alice` to see her grants.

Step 7. Stop everything and remove the data:

```shell
docker compose --file ledger/compose.yaml --project-name ledger down
docker volume prune -f
```

<p style="text-align: justify;">

Next, the [Ledger Guide](docs/ledger-guide.md) registers your own agents, grants them tools and connects real agents
such as Claude Code.

</p>

## Build and Test

Step 1. Compile every module:

```shell
mvn validate clean compile
```

Step 2. Run the unit tests:

```shell
mvn test
```

Step 3. Run every test, including the integration and end-to-end tests; Docker must be running for Testcontainers:

```shell
mvn verify
docker volume prune -f
```

Step 4. Build the jars without tests:

```shell
mvn package -DskipTests=true
```

<p style="text-align: justify;">

The web UI is built and tested with npm, as described in [Nexusphere Frontend](frontend/README.md). The SDKs, the
exporter, the adapters and the browser verifier use their own tools, as described in
[Python SDK](ledger/sdk/python/README.md#test), [TypeScript SDK](ledger/sdk/typescript/README.md#test),
[OpenTelemetry Exporter](ledger/otel-exporter/README.md#test), [Adapters](ledger/adapters/README.md#test) and
[Browser Verifier](ledger/verifier-web/README.md#test).

</p>

## Kubernetes

<p style="text-align: justify;">

`ledger/kube-dev.yaml` runs the ledger, PostgreSQL, Adminer, Keycloak, the web UI and the demo MCP server in the `dev`
namespace. The steps are in [Nexusphere Ledger](ledger/README.md#kubernetes).

</p>

## DevOps

The project ships [mvn-devops](https://github.com/oss-community/mvn-devops) 1.0.0 in the `mvn-devops` folder. It
starts the chosen tools in Docker, configures them and runs the pipeline stages against this Maven project, either from
the menu or from one of its 16 ready-made pipelines. Step-by-step use for this project, from `maven-sonarqube-nexus` up
to `jenkins-complete`, is in [DevOps Step by Step](docs/devops-guide.md); its own guide is
[mvn-devops/README.md](mvn-devops/README.md). Values and tokens are kept in `.devops/`, which is never committed.

```shell
mvn-devops/devops.sh doctor
mvn-devops/devops.sh pipelines
mvn-devops/devops.sh setup --pipeline maven-sonarqube-nexus
mvn-devops/devops.sh stages
mvn-devops/devops.sh run
mvn-devops/devops.sh urls
```

Windows:

```shell
mvn-devops\devops.bat setup
```

Upgrade with `mvn-devops/devops.sh upgrade`, which replaces the folder with the latest release after checking its
`SHA256SUMS`, or by deleting the `mvn-devops` folder and extracting the new release zip in its place under the same
name.

## Service URLs

| Service      | URL                                                                            | Sign in                                                |
|--------------|--------------------------------------------------------------------------------|--------------------------------------------------------|
| Web UI       | [http://localhost:5173](http://localhost:5173)                                 | Operator key, or `alice`/`alice` and `bob`/`bob`       |
| Ledger API   | [http://localhost:8090](http://localhost:8090)                                 | `Authorization: Bearer {key}`                          |
| Health       | [http://localhost:8090/actuator/health](http://localhost:8090/actuator/health) | None                                                   |
| Demo MCP     | http://localhost:8091/mcp                                                      | None, reached through the ledger at `/mcp/demo`        |
| Keycloak     | [http://localhost:8180](http://localhost:8180)                                 | `admin`/`admin`                                        |
| Adminer      | [http://localhost:8092](http://localhost:8092)                                 | Server `ledger-postgresql`, user and password `ledger` |
| Prometheus   | [http://localhost:9090](http://localhost:9090)                                 | None                                                   |
| Alertmanager | [http://localhost:9093](http://localhost:9093)                                 | None                                                   |
| Vault        | [http://localhost:8200](http://localhost:8200)                                 | Token `nexusphere-vault-development-token`             |
| OTLP         | localhost:4317 (gRPC), http://localhost:4318 (HTTP)                            | None, the Collector sends with the operator key        |

## Repository Layout

| Folder       | Content                                                                                    |
|--------------|--------------------------------------------------------------------------------------------|
| `ledger`     | The ledger: chain, mandate, server, verifier, demo MCP, tests, conformance, SDKs, adapters |
| `frontend`   | The web UI in React and TypeScript                                                         |
| `docs`       | The documents above and the PDF guide                                                      |
| `mvn-devops` | The build and delivery toolkit                                                             |

##

**<p align="center">[Top](#nexusphere)</p>**
