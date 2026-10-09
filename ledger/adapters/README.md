# <p align="center">Nexusphere Ledger Adapters</p>

<p align="center">Ready-made connections that put the tool calls of existing agent frameworks and gateways into the ledger.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Adapters](#adapters)
* [Record or Decide](#record-or-decide)
* [What an Entry Holds](#what-an-entry-holds)
* [Test](#test)

## Purpose

<p style="text-align: justify;">

An adapter sits beside an agent framework or a gateway that already runs the tool calls and turns each call into an
evidence entry, so nothing about the agent has to change but one line of setup. Every adapter records the same
shape of entry as the ledger's own MCP gateway: action `tools/call`, the tool name as the target, the agent, the
principal, SHA-256 hashes of the arguments and the result, and the outcome.

</p>

## Adapters

| Adapter                                      | Connects                         | How                                                     |
|----------------------------------------------|----------------------------------|---------------------------------------------------------|
| [LangGraph](langgraph/README.md)             | LangGraph and LangChain agents   | A callback handler, or tools wrapped with `govern`      |
| [OpenAI Agents SDK](openai-agents/README.md) | OpenAI Agents SDK agents         | Run hooks, and a tool input guardrail to decide         |
| [AgentCore](agentcore/README.md)             | Amazon Bedrock AgentCore Gateway | A Lambda function as REQUEST and RESPONSE interceptor   |
| [agentgateway](agentgateway/README.md)       | agentgateway                     | Its OpenTelemetry traces through the Collector exporter |

## Record or Decide

<p style="text-align: justify;">

Each adapter can only record what already happened, which leaves the decision with the framework or gateway, or ask
the ledger before the tool runs, which makes the ledger's grants the authority. agentgateway only records; its own
authorization policies decide.

</p>

| Mode   | Before the tool runs                        | After the tool runs                   | A denied call              |
|--------|---------------------------------------------|---------------------------------------|----------------------------|
| Record | Nothing                                     | One entry with the outcome            | Not possible               |
| Decide | `POST /api/v1/decisions` against the grants | `POST /api/v1/decisions/{id}/outcome` | Never runs; entry `DENIED` |

## What an Entry Holds

| Field           | LangGraph                 | OpenAI Agents SDK        | AgentCore                 | agentgateway                |
|-----------------|---------------------------|--------------------------|---------------------------|-----------------------------|
| `agentId`       | Set in the adapter        | Set in the adapter       | JWT claim or a fixed id   | Request header or JWT claim |
| `principalId`   | Run metadata or a default | Run context or a default | JWT claim or a default    | Request header or JWT claim |
| `target`        | Tool name                 | Tool name                | Tool name with its target | `gen_ai.tool.name`          |
| `correlationId` | LangGraph `thread_id`     | Agents SDK trace id      | `Mcp-Session-Id`          | Trace id                    |
| `inputHash`     | Tool arguments            | Tool arguments           | Tool arguments            | Not available in the span   |
| `outputHash`    | Tool result               | Tool result              | MCP `result`              | Not available in the span   |

## Test

Step 1. Start the ledger as in the [Quick Start](../../README.md#quick-start).

Step 2. Run the tests of each adapter as its README describes; every Python adapter has a live test that runs when
`NEXUSPHERE_LEDGER_URL` is set, and agentgateway has `e2e.sh`. Each must end with `OK` or `Result: PASSED`.

##

**<p align="center">[Top](#nexusphere-ledger-adapters)</p>**
