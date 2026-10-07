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
container. `LedgerClient` talks to it over HTTP with the development API key, like any outside caller, and rebuilds
evidence entries from the JSON so they can be checked with `ledger/chain` without trusting the server.

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
| `ApiKeyE2ETest`       | The API requires the ledger key                                              |
|                       | Health is open for probes                                                    |

##

**<p align="center">[Top](#nexusphere-ledger-end-to-end-tests)</p>**
