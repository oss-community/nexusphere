# <p align="center">Nexusphere Ledger for LangGraph</p>

<p align="center">Every tool call of a LangGraph or LangChain agent as evidence in the Nexusphere Ledger.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Install](#install)
* [Record Tool Calls](#record-tool-calls)
* [Let the Ledger Decide](#let-the-ledger-decide)
* [Reference](#reference)
* [Test](#test)

## Purpose

<p style="text-align: justify;">

`nexusphere-langgraph` connects the ledger to LangGraph without changing the graph. `NexusphereCallbackHandler`
records each tool call after it ran, with its outcome. `govern` wraps the tools so the ledger decides each call
against the principal's grants first, and a denied call never runs: the model gets the denial as the tool's error
message. Use one of the two for a tool, not both, or the call is recorded twice. Both work with `ToolNode`,
`create_react_agent` and any LangChain tool, synchronous or asynchronous.

</p>

## Install

Step 1. Install the SDK and the adapter from the repository; Python 3.10 or later is needed:

```shell
python3 -m pip install ledger/sdk/python ledger/adapters/langgraph
```

Step 2. Check the install; it must print `1.0.0`:

```shell
python3 -c "import nexusphere_langgraph; print(nexusphere_langgraph.__version__)"
```

## Record Tool Calls

<p style="text-align: justify;">

These steps need a running ledger, as in the [Quick Start](../../../README.md#quick-start), and an agent registered
with `POST /api/v1/agents`, as in the [Python SDK](../../sdk/python/README.md#record-evidence).

</p>

Step 1. Create the handler with the agent's own key:

```python
from nexusphere_ledger import LedgerClient
from nexusphere_langgraph import NexusphereCallbackHandler

ledger = LedgerClient("http://localhost:8090", agent_key)
handler = NexusphereCallbackHandler(ledger, "invoice-agent")
```

Step 2. Pass it with every run, and name the principal in the run metadata:

```python
graph.invoke({"messages": [("user", "Read invoice 7")]},
             {"callbacks": [handler], "metadata": {"nexusphere_principal": "alice"},
              "configurable": {"thread_id": "conversation-42"}})
```

Step 3. Check the evidence; one `tools/call` entry per tool call must appear, with `SUCCEEDED` or `FAILED`:

```shell
curl -s "http://localhost:8090/api/v1/evidence?agentId=invoice-agent" -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" | jq '.items[] | {action, target, principalId, outcome, correlationId}'
```

<p style="text-align: justify;">

A failure to reach the ledger is logged and does not stop the agent. With `strict=True` the tool call fails instead,
so no call runs without its evidence.

</p>

## Let the Ledger Decide

Step 1. Create a grant for the agent, as in the [Ledger Guide](../../../docs/ledger-guide.md#agents).

Step 2. Wrap the tools before building the graph:

```python
from langgraph.prebuilt import ToolNode
from nexusphere_langgraph import govern

tools = govern([read_invoice, send_email], ledger, principal_key="nexusphere_principal")
tool_node = ToolNode(tools)
```

Step 3. Run the graph with the principal in the metadata or the `configurable` part of the config:

```python
graph.invoke(inputs, {"configurable": {"nexusphere_principal": "alice", "thread_id": "conversation-42"}})
```

Step 4. Check the evidence; an allowed call shows `ALLOW` with its outcome, and a call outside the grant shows `DENY`
with `DENIED`, while the model reads `Denied by the Nexusphere Ledger: NOT_COVERED`.

## Reference

| Name                                                                                                                                  | Purpose                                                                |
|---------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| `NexusphereCallbackHandler(client, agent_id, principal_id=None, principal_key="nexusphere_principal", attributes=None, strict=False)` | Records each tool call after it ran                                    |
| `govern(tools, client, agent_id=None, principal_id=None, principal_key="nexusphere_principal", attributes=None)`                      | Returns tools whose calls the ledger decides first                     |
| `principal_id`                                                                                                                        | The principal when the run names none                                  |
| `principal_key`                                                                                                                       | The key in the run metadata or `configurable` that names the principal |
| `attributes`                                                                                                                          | Extra evidence attributes; `framework` is always `langgraph`           |

<p style="text-align: justify;">

The input hash is the SHA-256 of the tool arguments as canonical JSON, the output hash that of the result text, and
the correlation id the LangGraph `thread_id`. A `ToolMessage` with status `error` is recorded as `FAILED`.

</p>

## Test

Step 1. Install the adapter with LangGraph:

```shell
python3 -m pip install ./ledger/sdk/python "./ledger/adapters/langgraph[test]"
```

Step 2. Run the tests; they must end with `OK`:

```shell
cd ledger/adapters/langgraph && python3 -m unittest -v
```

Step 3. Run them against a running ledger; the two `LiveLedger` tests must pass instead of being skipped:

```shell
cd ledger/adapters/langgraph && NEXUSPHERE_LEDGER_URL=http://localhost:8090 python3 -m unittest -v tests.test_live_ledger
```

##

**<p align="center">[Top](#nexusphere-ledger-for-langgraph)</p>**
