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

`LedgerE2ETestBase` starts the ledger on a free port, which is also its mandate issuer address, with the `postgresql`
and `dev` profiles against a PostgreSQL 18 container. `LedgerClient` talks to it over HTTP with the development API key or a registered agent's key, like any
outside caller, and rebuilds evidence entries from the JSON so they can be checked with `ledger/chain` without trusting
the server. `FakeMcpServer` is a small MCP server on a random port, configured as the `files` server of the gateway; it
answers `initialize`, `tools/list` and `tools/call`, and streams the answer of tools ending in `_sse`.
`A2aE2ETest` starts a second ledger, `SupplierLedger`, with its own PostgreSQL container and signing key, which trusts
the first one and forwards to `FakeA2aAgent`, which streams `message/stream` and `tasks/resubscribe`, so mandates,
request proofs and receipts cross between two ledgers over HTTP.
`PrincipalConsentE2ETest` starts a `StandaloneLedger` that trusts `FakeOidcProvider`, a small OpenID Connect provider
on a random port that signs RS256 tokens for any principal.
`KeyRotationE2ETest` restarts a `StandaloneLedger` against one PostgreSQL container with a new signing key, so the
rotation happens at startup as it does in production.
`WitnessE2ETest` starts two `StandaloneLedger`s with their own PostgreSQL containers: one is the log and asks the
other, which watches it, to cosign its log checkpoints over the C2SP witness protocol.

</p>

## Test Classes

| Class                     | Scenario                                                                      |
|---------------------------|-------------------------------------------------------------------------------|
| `EvidenceE2ETest`         | Recorded evidence is sealed and linked to the previous entry                  |
|                           | Evidence is read back exactly as recorded, with microsecond timestamps        |
|                           | Evidence is listed per agent and per principal in pages                       |
|                           | A denied action is recorded with its reason                                   |
|                           | Invalid evidence is rejected with field errors                                |
|                           | Malformed requests, unknown evidence and bad paging are reported clearly      |
| `VerificationE2ETest`     | The ledger verifies its own chain and checkpoints                             |
|                           | A checkpoint is verified offline with the published key                       |
|                           | An outsider rebuilds and verifies the whole chain from the API                |
| `PackageE2ETest`          | A package discloses only the requested evidence and verifies offline          |
|                           | A package starts at the last checkpoint before the evidence                   |
|                           | Any change to a package is detected                                           |
|                           | Packages are for the operator and need matching evidence                      |
|                           | The operator can have the ledger verify a package                             |
| `ApiKeyE2ETest`           | The API requires the ledger key                                               |
|                           | Health is open for probes                                                     |
| `RateLimitE2ETest`        | A caller over its rate limit is told when to retry                            |
| `AgentE2ETest`            | A registered agent gets its own key once                                      |
|                           | Registering the same agent twice is a conflict                                |
|                           | A disabled agent or an old key is rejected                                    |
|                           | Agent lifecycle is recorded as evidence                                       |
|                           | An agent cannot do what only the operator may                                 |
|                           | An agent records and reads only its own evidence                              |
| `GrantE2ETest`            | A grant is created and its terms can be recomputed from the evidence          |
|                           | A revoked grant is recorded and shown as revoked                              |
|                           | Invalid grants are rejected with field errors                                 |
|                           | Grants need an active agent                                                   |
|                           | An agent sees only its own grants                                             |
| `DecisionE2ETest`         | A covered action is allowed and its outcome is recorded                       |
|                           | Every denial is recorded with its reason                                      |
|                           | The operator can ask for a decision on behalf of an agent                     |
|                           | Outcomes are reported once by the deciding agent for allowed actions only     |
|                           | An agent cannot ask for another agent's decision and the chain stays valid    |
| `McpGatewayE2ETest`       | An allowed tool call is forwarded and recorded with its outcome               |
|                           | A denied tool call never reaches the server                                   |
|                           | Tool errors are recorded as failed outcomes                                   |
|                           | Streamed responses are returned as JSON                                       |
|                           | A streamed answer is relayed live with the server requests inside             |
|                           | The server stream is relayed on GET                                           |
|                           | Other messages pass through with the session                                  |
|                           | Bad requests are answered as JSON-RPC errors                                  |
|                           | An unreachable server is recorded as a failed call                            |
| `MandateE2ETest`          | An agent obtains a mandate that anyone can verify with the published key      |
|                           | Revoking a mandate sets its bit in the signed status list                     |
|                           | Revoking the grant revokes every mandate issued from it                       |
|                           | An agent cannot obtain or revoke mandates it does not own                     |
|                           | A mandate cannot outlive its grant                                            |
|                           | The keys and status list are public and signed                                |
| `MandateVerifierE2ETest`  | Another organization verifies a mandate from the public endpoints only        |
|                           | The verifier sees a revocation as soon as the status list is refetched        |
|                           | The verifier rejects what the mandate does not allow                          |
| `A2aE2ETest`              | An allowed message carries a mandate and both ledgers hold matching evidence  |
|                           | A denied message never leaves the ledger                                      |
|                           | A failed task is recorded as failed on both sides with a receipt              |
|                           | The receiver rejects a revoked mandate and records the denial                 |
|                           | Replayed, tampered or forged requests are rejected without reaching the agent |
|                           | The receiver counts the uses of a mandate on its own                          |
|                           | An unreachable peer is answered without delivery                              |
|                           | A streamed task is relayed live and the receipt covers every event            |
|                           | A stream that ends in a failed task is recorded as failed on both sides       |
| `PrincipalConsentE2ETest` | A principal signs in with an OIDC token that only opens the principal API     |
|                           | An operator grant waits for the principal's consent                           |
|                           | An agent asks for a grant and the principal denies it                         |
|                           | A principal grants and revokes on its own                                     |
|                           | A principal cannot see or decide another principal's grants                   |
| `KeyRotationE2ETest`      | An endorsed rotation keeps earlier checkpoints and mandates valid             |
|                           | A new key without the previous key is refused unless the operator allows it   |
|                           | A retired key cannot sign again                                               |
| `TransparencyLogE2ETest`  | The log checkpoint is a signed note over the Merkle root                      |
|                           | An entry is proven in the signed tree                                         |
|                           | A later tree is proven to extend an earlier one                               |
|                           | A package proves its entries in the log                                       |
|                           | Verification checks the Merkle tree and log checkpoints                       |
|                           | This ledger witnesses no log unless configured                                |
| `WitnessE2ETest`          | A witness cosigns every checkpoint that extends what it saw                   |
|                           | A witness refuses a forked or stale checkpoint                                |

##

**<p align="center">[Top](#nexusphere-ledger-end-to-end-tests)</p>**
