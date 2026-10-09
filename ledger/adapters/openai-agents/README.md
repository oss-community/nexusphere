# <p align="center">Nexusphere Ledger for the OpenAI Agents SDK</p>

<p align="center">Every tool call of an OpenAI Agents SDK agent as evidence in the Nexusphere Ledger.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Install](#install)
* [Record Tool Calls](#record-tool-calls)
* [Let the Ledger Decide](#let-the-ledger-decide)
* [Reference](#reference)
* [Test](#test)

## Purpose

<p style="text-align: justify;">

`nexusphere-openai-agents` connects the ledger to the OpenAI Agents SDK through its own extension points.
`NexusphereHooks` is a set of run hooks that records each function tool call with its outcome. `hooks.govern(tools)`
adds a tool input guardrail, so the ledger decides each call against the principal's grants before it runs; a denied
call never runs and the model reads the denial instead of a result. The same hooks then report the outcome of the
allowed calls.

</p>

## Install

Step 1. Install the SDK and the adapter from the repository; Python 3.10 or later is needed:

```shell
python3 -m pip install ledger/sdk/python ledger/adapters/openai-agents
```

Step 2. Check the install; it must print `1.0.0`:

```shell
python3 -c "import nexusphere_openai_agents; print(nexusphere_openai_agents.__version__)"
```

## Record Tool Calls

<p style="text-align: justify;">

These steps need a running ledger, as in the [Quick Start](../../../README.md#quick-start), and an agent registered
with `POST /api/v1/agents`, as in the [Python SDK](../../sdk/python/README.md#record-evidence).

</p>

Step 1. Create the hooks with the agent's own key, and let them see tool failures:

```python
from agents import Agent, Runner
from nexusphere_ledger import LedgerClient
from nexusphere_openai_agents import NexusphereHooks

hooks = NexusphereHooks(LedgerClient("http://localhost:8090", agent_key), "invoice-agent")
agent = Agent(name="Invoices", instructions="Handle invoices.",
              tools=hooks.govern([read_invoice, send_email], decide=False))
```

Step 2. Run the agent with the hooks and the principal in the run context:

```python
result = await Runner.run(agent, "Read invoice 7", context={"principal_id": "alice"}, hooks=hooks)
```

Step 3. Check the evidence; one `tools/call` entry per tool call must appear, with `SUCCEEDED` or `FAILED`:

```shell
curl -s "http://localhost:8090/api/v1/evidence?agentId=invoice-agent" -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" | jq '.items[] | {action, target, principalId, outcome, correlationId}'
```

<p style="text-align: justify;">

Without `govern` the hooks still record every call, but a tool that raised is recorded as `SUCCEEDED`, because the
SDK hands the hooks the error message as an ordinary result. A failure to reach the ledger is logged and does not stop
the agent; with `strict=True` the run fails instead.

</p>

## Let the Ledger Decide

Step 1. Create a grant for the agent, as in the [Ledger Guide](../../../docs/ledger-guide.md#agents).

Step 2. Govern the tools; `decide` is on by default:

```python
agent = Agent(name="Invoices", instructions="Handle invoices.", tools=hooks.govern([read_invoice, send_email]))
```

Step 3. Run the agent with the same hooks, as in [Record Tool Calls](#record-tool-calls) Step 2.

Step 4. Check the evidence; an allowed call shows `ALLOW` with its outcome, and a call outside the grant shows `DENY`
with `DENIED`, while the model reads `Denied by the Nexusphere Ledger: NOT_COVERED`.

## Reference

| Name                                                                                                       | Purpose                                                          |
|------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `NexusphereHooks(client, agent_id=None, principal_id=None, principal=None, attributes=None, strict=False)` | Run hooks that record or report each tool call                   |
| `hooks.govern(tools, decide=True)`                                                                         | Adds the ledger guardrail and failure tracking to function tools |
| `hooks.guardrail`                                                                                          | The tool input guardrail, for tools built by hand                |
| `principal`                                                                                                | A function from the run context to the principal                 |
| `principal_id`                                                                                             | The principal when the context names none                        |

<p style="text-align: justify;">

Without `principal`, the principal is the `principal_id` key of a dictionary context or the `principal_id` attribute
of any other context. The input hash is the SHA-256 of the tool arguments as canonical JSON, the output hash that of
the result, and the correlation id the Agents SDK trace id.

</p>

## Test

Step 1. Run the tests; they must end with `OK`:

```shell
cd ledger/adapters/openai-agents && python3 -m unittest -v
```

Step 2. Run them against a running ledger; the two `LiveLedger` tests must pass instead of being skipped:

```shell
cd ledger/adapters/openai-agents && NEXUSPHERE_LEDGER_URL=http://localhost:8090 python3 -m unittest -v tests.test_live_ledger
```

<p style="text-align: justify;">

The tests run real agents on the SDK's own `ScriptedModel`, so no model and no OpenAI key are needed.

</p>

##

**<p align="center">[Top](#nexusphere-ledger-for-the-openai-agents-sdk)</p>**
