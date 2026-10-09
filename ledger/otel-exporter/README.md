# <p align="center">Nexusphere Ledger OpenTelemetry Exporter</p>

<p align="center">Turn the GenAI spans agents already emit into ledger evidence, through the OpenTelemetry Collector.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Mapping](#mapping)
* [Configuration](#configuration)
* [Run with Docker](#run-with-docker)
* [Build the Collector](#build-the-collector)
* [Add It to Your Collector](#add-it-to-your-collector)
* [Test](#test)

## Purpose

<p style="text-align: justify;">

Most agent frameworks and gateways already emit OpenTelemetry spans that follow the GenAI and MCP semantic
conventions. The `nexusphere` exporter is a Collector component that picks the spans of tool calls and agent
invocations and records each one as evidence through `POST /api/v1/evidence/batch`. An agent needs no code change and
no SDK: point its OTLP exporter at a Collector that has this exporter in its traces pipeline. The resulting entries get
the same hash chain, transparency log, witnesses, statements and packages as any other evidence. A ready configuration for
agentgateway is in [Adapters](../adapters/agentgateway/README.md).

</p>

<p style="text-align: justify;">

Tool arguments and results never reach the ledger. When a span carries `gen_ai.tool.call.arguments` or
`gen_ai.tool.call.result`, the exporter sends only their SHA-256 as `inputHash` and `outputHash`, so the ledger can
later prove which input and output belonged to the call without holding them.

</p>

## Mapping

| Evidence field  | Taken from                                                                                                   |
|-----------------|--------------------------------------------------------------------------------------------------------------|
| selected spans  | `gen_ai.operation.name` or `mcp.method.name` listed in `operations` (default `execute_tool`, `invoke_agent`, `tools/call`) |
| `agentId`       | The first of `agent_attributes` (default `gen_ai.agent.id`, `gen_ai.agent.name`), else `default_agent`       |
| `principalId`   | The first of `principal_attributes` (default `nexusphere.principal.id`, `user.id`, `enduser.id`), else `default_principal` |
| `action`        | `mcp.method.name`; `tools/call` for `execute_tool`; `agents/invoke` for `invoke_agent`; else `gen_ai/{operation}` |
| `target`        | `gen_ai.tool.name`; the invoked `gen_ai.agent.name` for `invoke_agent`; else the span name                   |
| `occurredAt`    | The span start time                                                                                          |
| `decision`      | `nexusphere.decision` (`ALLOW` or `DENY`) when the gateway sets it                                           |
| `outcome`       | `DENIED` after a `DENY`, `FAILED` when the span status is an error or `mcp.error.code` is set, else `SUCCEEDED` |
| `reason`        | `nexusphere.reason`, else `error.type` or the status message of a failed span                                |
| `delegationId`  | `nexusphere.delegation.id`                                                                                   |
| `inputHash`     | `nexusphere.input.hash`, else the SHA-256 of `gen_ai.tool.call.arguments`                                    |
| `outputHash`    | `nexusphere.output.hash`, else the SHA-256 of `gen_ai.tool.call.result`                                      |
| `correlationId` | `gen_ai.conversation.id`, else the trace ID                                                                  |
| `attributes`    | `otel.trace_id`, `otel.span_id`, `otel.span_name`, `otel.duration_ms`, the model, provider, tool call ID, `mcp.session.id`, `mcp.target` and `service.name`, and the keys in `attributes` |

<p style="text-align: justify;">

Span attributes win over resource attributes. A selected span without an agent or a principal is skipped and logged at
debug level. Text is cut to the ledger's limits and control characters are replaced, so one odd span never makes the
ledger refuse a whole batch. A batch the ledger still refuses (HTTP 4xx) is dropped and logged; network errors, 429 and
5xx are retried with the Collector's retry and queue settings. A batch that reached the ledger but whose answer was
lost is sent again, so the same span can be recorded twice; `otel.span_id` tells such entries apart.

</p>

## Configuration

| Setting                | Default                                               | Meaning                                                                  |
|------------------------|-------------------------------------------------------|--------------------------------------------------------------------------|
| `endpoint`             | none, required                                        | Base URL of the ledger, such as `http://localhost:8090`                   |
| `api_key`              | none, required                                        | An agent API key, which records only that agent, or the operator key      |
| `operations`           | `execute_tool`, `invoke_agent`, `tools/call`          | Values of `gen_ai.operation.name` or `mcp.method.name` that become evidence |
| `agent_attributes`     | `gen_ai.agent.id`, `gen_ai.agent.name`                | Attributes that name the agent, first match wins                         |
| `default_agent`        | empty                                                 | Agent for spans without one                                              |
| `principal_attributes` | `nexusphere.principal.id`, `user.id`, `enduser.id`    | Attributes that name the principal, first match wins                     |
| `default_principal`    | empty                                                 | Principal for spans without one                                          |
| `attributes`           | empty                                                 | More span or resource attributes to copy into the evidence attributes    |
| `hash_content`         | `true`                                                | Hash tool arguments and results into `inputHash` and `outputHash`         |
| `batch_size`           | `500`                                                 | Entries per request, at most 500                                         |
| `timeout`, `headers`, `tls`, `sending_queue`, `retry_on_failure` | Collector defaults, 30 s timeout | The standard Collector HTTP client, queue and retry settings |

```yaml
exporters:
  nexusphere:
    endpoint: http://localhost:8090
    api_key: ${env:NEXUSPHERE_LEDGER_API_KEY}
    default_principal: acme

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [nexusphere]
```

## Run with Docker

<p style="text-align: justify;">

The compose file of the ledger starts the Collector as `otel-collector` with the operator key of the `dev` profile, and
`ledger/kube-dev.yaml` runs it in the `dev` namespace. Agents send OTLP to it on `4317` (gRPC) or `4318` (HTTP).

</p>

Step 1. Start the ledger as in the [Quick Start](../../README.md#quick-start); `otel-collector` starts with it.

Step 2. Send one tool call span over OTLP/HTTP; the answer must be `{"partialSuccess":{}}`:

```shell
curl -X POST http://localhost:4318/v1/traces -H "Content-Type: application/json" -d '{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"invoice-service"}}]},"scopeSpans":[{"spans":[{"traceId":"5b8efff798038103d269b633813fc60c","spanId":"eee19b7ec3c1b174","name":"execute_tool read_invoice","kind":1,"startTimeUnixNano":"'$(date +%s)000000000'","endTimeUnixNano":"'$(date +%s)100000000'","attributes":[{"key":"gen_ai.operation.name","value":{"stringValue":"execute_tool"}},{"key":"gen_ai.tool.name","value":{"stringValue":"read_invoice"}},{"key":"gen_ai.agent.id","value":{"stringValue":"otel-agent"}},{"key":"user.id","value":{"stringValue":"alice"}}]}]}]}]}'
```

Step 3. Check the evidence; one entry with `tools/call` on `read_invoice` for `alice` must appear:

```shell
curl -s "http://localhost:8090/api/v1/evidence?agentId=otel-agent" -H "Authorization: Bearer nexusphere-ledger-development-key-change-me" | jq '.items[] | {action, target, principalId, outcome}'
```

## Build the Collector

<p style="text-align: justify;">

`collector/builder-config.yaml` describes a Collector distribution, `nexusphere-otelcol`, with the OTLP receiver, the
batch processor, the debug exporter and this exporter, built with the OpenTelemetry Collector Builder.

</p>

Step 1. Check Go; the version must be 1.26 or later:

```shell
go version
```

Step 2. Install the builder:

```shell
go install go.opentelemetry.io/collector/cmd/builder@v0.162.0
```

Step 3. Build the distribution; the last line names `./_build/nexusphere-otelcol`:

```shell
cd ledger/otel-exporter/collector
$(go env GOPATH)/bin/builder --config builder-config.yaml
```

Step 4. Run it against a ledger with an agent key; it must log `Everything is ready`:

```shell
NEXUSPHERE_LEDGER_URL=http://localhost:8090 NEXUSPHERE_LEDGER_API_KEY={agentApiKey} NEXUSPHERE_DEFAULT_PRINCIPAL=acme ./_build/nexusphere-otelcol --config config.yaml
```

## Add It to Your Collector

<p style="text-align: justify;">

A Collector built with the builder takes the exporter as one more entry under `exporters` in its own builder
configuration; the module path is `github.com/oss-community/nexusphere/ledger/otel-exporter` and its package name is
`nexusphereexporter`.

</p>

```yaml
exporters:
  - gomod: github.com/oss-community/nexusphere/ledger/otel-exporter v1.0.0
    name: nexusphereexporter
```

## Test

Step 1. Run the unit tests; they must end with `ok`:

```shell
cd ledger/otel-exporter
go test ./...
```

Step 2. Run them against a running ledger; `TestLiveLedger` must pass instead of being skipped:

```shell
NEXUSPHERE_LEDGER_URL=http://localhost:8090 go test -count=1 -v -run TestLiveLedger ./...
```

##

**<p align="center">[Top](#nexusphere-ledger-opentelemetry-exporter)</p>**
