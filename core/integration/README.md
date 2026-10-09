# <p align="center">Nexusphere Core Integration</p>

<p align="center">Adapters between the core and outside agents, machines and the ledger.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)
* [API](#api)
* [Ledger](#ledger)

## Purpose

<p style="text-align: justify;">

The core speaks its own model; agents and machines speak protocols. This module translates between them so the domain
modules stay protocol-free: an agent gateway in JSON-RPC with an agent card, and a task gateway for machines. Protocols
such as A2A and MCP are reached through adapters here, not reimplemented in the domain. It also connects the core to a
Nexusphere Ledger, so what happens in the core becomes verifiable evidence and delegations become ledger grants.

</p>

## Responsibilities

* Publish an agent card for a network
* Answer JSON-RPC calls from agents: `capabilities/discover`, `capabilities/get`, `agreements/propose`,
  `agreements/get`, `transactions/request`, `transactions/get`
* Let machines start, complete and fail the tasks assigned to them
* Write domain events to a ledger outbox in the same transaction and forward them to the ledger in order
* Keep a ledger grant for every active delegation and issue mandates from it
* Publish `nexusphere_ledger_outbox_pending` and `nexusphere_ledger_outbox_delivered_total` for monitoring
* Owns the `integration` schema: `ledger_outbox` and `ledger_grant`

## Dependencies

| Depends on                                                                                     | Used by     |
|------------------------------------------------------------------------------------------------|-------------|
| `shared`, `membership`, `discovery`, `agreement`, `transaction`, `authorization`, `delegation` | `bootstrap` |

## API

| Path                                                                | Purpose                            |
|---------------------------------------------------------------------|------------------------------------|
| `/api/v1/networks/{networkId}/agent`                                | Agent card and JSON-RPC endpoint   |
| `/api/v1/networks/{networkId}/machine/tasks`                        | Machine task gateway               |
| `/api/v1/networks/{networkId}/delegations/{delegationId}/mandates`  | Ledger mandate for a delegation    |

## Ledger

<p style="text-align: justify;">

The link is off until `nexusphere.ledger.url` (`APP_LEDGER_URL`) is set. The core then calls the ledger with the
operator key in `nexusphere.ledger.api-key` (`APP_LEDGER_API_KEY`). A listener writes each domain event to
`integration.ledger_outbox` inside the transaction that produced it, so an event is forwarded only if its change was
committed. A scheduled forwarder, one at a time across instances through a PostgreSQL advisory lock, sends the outbox
in order every `forward-interval` (`APP_LEDGER_FORWARD_INTERVAL`, default 5s) until it is empty, reading
`batch-size` (`APP_LEDGER_BATCH_SIZE`, default 100, at most 500) messages at a time. Consecutive evidence goes to the
ledger's `POST /api/v1/evidence/batch` in one call.

</p>

| Core event                              | Ledger evidence                                                                                      |
|-----------------------------------------|------------------------------------------------------------------------------------------------------|
| Authorization granted or denied         | Agent is the acting principal, principal is the delegator when a delegation was used; ALLOW or DENY  |
| Delegation granted, resumed             | `delegation/grant` or `delegation/resume` from the delegator to the delegate, and a new ledger grant |
| Delegation revoked, suspended           | `delegation/revoke` or `delegation/suspend`, and the ledger grant is revoked                         |
| Other event with an acting principal    | The event type as the action                                                                         |

<p style="text-align: justify;">

Each entry carries the core correlation id and the attributes `core.event`, `core.eventId` and `core.network`, plus
`core.decisionId` for decisions. A ledger grant has the delegator as principal, the delegate as agent, the delegation's
actions, its resource types as targets (`*` when it has none), and the delegation's validity, capped at 365 days. The
delegate is registered as a ledger agent first when it is not one yet.

</p>

<p style="text-align: justify;">

When the ledger cannot be reached or answers 401, 403, 408, 425, 429 or 5xx, forwarding stops and retries the same
message on the next run, so nothing is skipped. Any other 4xx means the ledger refused the message for good: it is
marked rejected with its error and the rest goes on; a refused batch is retried one entry at a time, so only the
refused entries are rejected. Delivery is at least once, so a crash between the ledger's answer
and the commit can send an entry twice.

</p>

<p style="text-align: justify;">

The delegator or the delegate of an active delegation can ask for a mandate with an `audience` and an `expiresAt`; the
answer is the ledger's mandate with its signed token. The call answers 409 `DELEGATION_NOT_IN_LEDGER` until the
forwarder has created the grant, and 409 `DELEGATION_NOT_ACTIVE` for a suspended or revoked delegation. When the ledger
runs with principal sign-in, grants created by the core wait for the principal's approval before mandates can be
issued. The agent's own ledger API key is not kept: the ledger operator issues one with `POST /api/v1/agents/{id}/key`
when the agent should call the ledger itself.

</p>

```shell
curl -X POST http://localhost:8080/api/v1/networks/{networkId}/delegations/{delegationId}/mandates -H "Authorization: Bearer {agentToken}" -H "Content-Type: application/json" -d '{"audience":"https://supplier.example"}'
```

##

**<p align="center">[Top](#nexusphere-core-integration)</p>**
