# <p align="center">Nexusphere Ledger for agentgateway</p>

<p align="center">Every MCP tool call that passes agentgateway as evidence in the Nexusphere Ledger.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Run](#run)
* [Identity from a Token](#identity-from-a-token)
* [What Is Recorded](#what-is-recorded)
* [Test](#test)

## Purpose

<p style="text-align: justify;">

agentgateway already traces every MCP request with OpenTelemetry, including `mcp.method.name`, `gen_ai.tool.name`
and the JSON-RPC error of a failed call. `config.yaml` sends those traces to the Collector of the
[OpenTelemetry Exporter](../../otel-exporter/README.md), which turns each `tools/call` span into an evidence entry.
Two trace fields name the agent and the principal; here they come from request headers. agentgateway keeps deciding
with its own authorization policies; the ledger records.

</p>

## Run

Step 1. Start the ledger and the Collector as in the [Quick Start](../../../README.md#quick-start); the compose file
runs the Collector as `otel-collector` on 4317 and the demo MCP server on 8091.

Step 2. Install agentgateway as its [documentation](https://agentgateway.dev/docs) describes, and check it; the
command must print the version:

```shell
agentgateway --version
```

Step 3. Start agentgateway with the configuration; the log must end with `started bind`:

```shell
agentgateway -f ledger/adapters/agentgateway/config.yaml
```

Step 4. Run the end-to-end check in another terminal; it must end with `Result: PASSED`:

```shell
ledger/adapters/agentgateway/e2e.sh
```

<p style="text-align: justify;">

To proxy your own MCP servers, replace the `demo` target in `config.yaml`; the `config.tracing` part stays as it is.
On a host without IPv6, start agentgateway with `IPV6_ENABLED=false`.

</p>

## Identity from a Token

<p style="text-align: justify;">

With `mcpAuthentication` on the route, agentgateway validates the caller's JWT, and the trace fields can name the
agent and the principal from its claims instead of headers:

</p>

```yaml
config:
  tracing:
    otlpEndpoint: http://localhost:4317
    randomSampling: true
    fields:
      add:
        gen_ai.agent.id: jwt.azp
        nexusphere.principal.id: jwt.sub
```

## What Is Recorded

| Evidence field  | From the agentgateway span                                             |
|-----------------|------------------------------------------------------------------------|
| `agentId`       | `gen_ai.agent.id`, set by the trace field                              |
| `principalId`   | `nexusphere.principal.id`, set by the trace field                      |
| `target`        | `gen_ai.tool.name`                                                     |
| `outcome`       | `FAILED` with `JSON-RPC {code} {message}` when `mcp.error.code` is set |
| `correlationId` | The trace id                                                           |
| `attributes`    | `mcp.target`, `mcp.session.id`, `service.name` and the span ids        |

<p style="text-align: justify;">

agentgateway does not put tool arguments or results into its spans, so the entries carry no input or output hash,
and a tool result with `isError` is recorded as `SUCCEEDED`. Where those matter, use the ledger's own MCP gateway or
one of the framework adapters.

</p>

## Test

<p style="text-align: justify;">

`e2e.sh` opens an MCP session through agentgateway as a new agent for `alice`, calls `read_file` and a tool that does
not exist, waits for the Collector, and checks that the ledger holds a `SUCCEEDED` and a `FAILED` entry. It reads
`AGENTGATEWAY_URL`, `NEXUSPHERE_LEDGER_URL` and `NEXUSPHERE_LEDGER_API_KEY`, and defaults to the local setup above.

</p>

##

**<p align="center">[Top](#nexusphere-ledger-for-agentgateway)</p>**
