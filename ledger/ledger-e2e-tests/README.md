# <p align="center">Nexusphere Ledger End-to-End Tests</p>

## <p align="center">Table of Content</p>

* [Getting Started](#getting-started)
* [Test Harness](#test-harness)
* [Test Classes](#test-classes)

## Getting Started

### Prerequisites

* [Java 21](https://www.oracle.com/java/technologies/downloads)
* [Maven 3](https://maven.apache.org/index.html)
* [Docker](https://www.docker.com)

### Run Only This Module

```shell
mvn -pl ledger/ledger-e2e-tests -am verify
```

### Run One Test Class

```shell
mvn -pl ledger/ledger-e2e-tests -am verify -Dit.test=VerificationE2ETest -Dfailsafe.failIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

## Test Harness

<p style="text-align: justify;">

`LedgerE2ETestBase` starts the ledger on a random port with the `postgresql` and `dev` profiles against a PostgreSQL 18
container. `LedgerClient` talks to it over HTTP with the development API key or a registered agent's key, like any
outside caller, and rebuilds evidence entries from the JSON so they can be checked with `ledger/chain` without trusting
the server. `FakeMcpServer` is a small MCP server on a random port, configured as the `files` server of the gateway; it
answers `initialize`, `tools/list` and `tools/call`, and streams the answer of tools ending in `_sse`.

</p>

## Test Classes

| Class                 | Scenario                                                                     |
|-----------------------|------------------------------------------------------------------------------|
| `EvidenceE2ETest`     | Recorded evidence is sealed and linked to the previous entry                 |
|                       | Evidence is read back exactly as recorded, with microsecond timestamps       |
|                       | Evidence is listed per agent and per principal in pages                      |
|                       | A denied action is recorded with its reason                                  |
|                       | Invalid evidence is rejected with field errors                               |
|                       | Malformed requests, unknown evidence and bad paging are reported clearly    |
| `VerificationE2ETest` | The ledger verifies its own chain and checkpoints                            |
|                       | A checkpoint is verified offline with the published key                      |
|                       | An outsider rebuilds and verifies the whole chain from the API               |
| `PackageE2ETest`      | A package discloses only the requested evidence and verifies offline         |
|                       | A package starts at the last checkpoint before the evidence                  |
|                       | Any change to a package is detected                                          |
|                       | Packages are for the operator and need matching evidence                     |
| `ApiKeyE2ETest`       | The API requires the ledger key                                              |
|                       | Health is open for probes                                                    |
| `AgentE2ETest`        | A registered agent gets its own key once                                     |
|                       | Registering the same agent twice is a conflict                               |
|                       | A disabled agent or an old key is rejected                                   |
|                       | Agent lifecycle is recorded as evidence                                      |
|                       | An agent cannot do what only the operator may                                |
|                       | An agent records and reads only its own evidence                             |
| `GrantE2ETest`        | A grant is created and its terms can be recomputed from the evidence         |
|                       | A revoked grant is recorded and shown as revoked                             |
|                       | Invalid grants are rejected with field errors                                |
|                       | Grants need an active agent                                                  |
|                       | An agent sees only its own grants                                            |
| `DecisionE2ETest`     | A covered action is allowed and its outcome is recorded                      |
|                       | Every denial is recorded with its reason                                     |
|                       | The operator can ask for a decision on behalf of an agent                    |
|                       | Outcomes are reported once by the deciding agent for allowed actions only    |
|                       | An agent cannot ask for another agent's decision and the chain stays valid   |
| `McpGatewayE2ETest`   | An allowed tool call is forwarded and recorded with its outcome              |
|                       | A denied tool call never reaches the server                                  |
|                       | Tool errors are recorded as failed outcomes                                  |
|                       | Streamed responses are returned as JSON                                      |
|                       | Other messages pass through with the session                                 |
|                       | Bad requests are answered as JSON-RPC errors                                 |
|                       | An unreachable server is recorded as a failed call                           |

##

**<p align="center">[Top](#nexusphere-ledger-end-to-end-tests)</p>**
