# <p align="center">Nexusphere Ledger for Amazon Bedrock AgentCore</p>

<p align="center">An AgentCore Gateway interceptor that records or decides every MCP tool call in the Nexusphere Ledger.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Build the Function](#build-the-function)
* [Deploy](#deploy)
* [Settings](#settings)
* [Identity](#identity)
* [Test](#test)

## Purpose

<p style="text-align: justify;">

`nexusphere-agentcore` is an AWS Lambda function for the REQUEST and RESPONSE interceptors of an Amazon Bedrock
AgentCore Gateway with MCP targets. In `record` mode it lets every request through and records each `tools/call` with
its outcome when the response passes. In `decide` mode the REQUEST interceptor asks the ledger first: a denied call is
answered at once with a JSON-RPC error `-32003`, the same answer the ledger's own MCP gateway gives, and never reaches
the target; an allowed call carries its decision id in `params._meta`, and the RESPONSE interceptor reports its
outcome. Other MCP methods pass unchanged.

</p>

## Build the Function

Step 1. Install the dependencies for the Lambda runtime into a build folder; the runtime here is Python 3.13 on
x86_64:

```shell
python3 -m pip install --target build/agentcore --platform manylinux2014_x86_64 --python-version 3.13 --only-binary=:all: "cryptography>=42"
python3 -m pip install --target build/agentcore --no-deps ledger/sdk/python ledger/adapters/agentcore
```

Step 2. Pack the folder:

```shell
cd build/agentcore && zip -qr ../nexusphere-agentcore.zip . && cd ../..
```

Step 3. Check the package; the list must contain `nexusphere_agentcore/interceptor.py`:

```shell
unzip -l build/nexusphere-agentcore.zip | grep nexusphere_agentcore/
```

## Deploy

<p style="text-align: justify;">

The ledger must be reachable from the Lambda function, for example through a VPC or a public HTTPS address. The
commands use the AWS CLI; `{account}`, `{region}` and the role names are your own.

</p>

Step 1. Create the function with the handler `nexusphere_agentcore.lambda_handler`:

```shell
aws lambda create-function --function-name nexusphere-interceptor --runtime python3.13 --architectures x86_64 --handler nexusphere_agentcore.lambda_handler --zip-file fileb://build/nexusphere-agentcore.zip --role arn:aws:iam::{account}:role/nexusphere-interceptor-role --timeout 10 --environment "Variables={NEXUSPHERE_LEDGER_URL=https://ledger.example.com,NEXUSPHERE_LEDGER_API_KEY={key},NEXUSPHERE_MODE=record}"
```

Step 2. Allow the gateway service role to invoke the function, as in the AgentCore guide on interceptor permissions.

Step 3. Add the function to the gateway as both interceptors, with the request headers, so the interceptor sees the
caller's token:

```shell
aws bedrock-agentcore-control update-gateway --gateway-identifier {gatewayId} --name {gatewayName} --role-arn arn:aws:iam::{account}:role/{gatewayRole} --protocol-type MCP --authorizer-type CUSTOM_JWT --authorizer-configuration file://authorizer.json --interceptor-configurations '[{"interceptor":{"lambda":{"arn":"arn:aws:lambda:{region}:{account}:function:nexusphere-interceptor"}},"interceptionPoints":["REQUEST","RESPONSE"],"inputConfiguration":{"passRequestHeaders":true}}]'
```

Step 4. Call a tool through the gateway, then check the evidence; one `tools/call` entry with the tool name as
target, such as `invoices___read_invoice`, must appear:

```shell
curl -s "https://ledger.example.com/api/v1/evidence?agentId={agentId}" -H "Authorization: Bearer {operatorKey}" | jq '.items[] | {action, target, principalId, outcome, correlationId}'
```

## Settings

| Variable                       | Default     | Meaning                                                                         |
|--------------------------------|-------------|---------------------------------------------------------------------------------|
| `NEXUSPHERE_LEDGER_URL`        | (required)  | Address of the ledger                                                           |
| `NEXUSPHERE_LEDGER_API_KEY`    | (required)  | The operator key for many agents, or one agent's key with `NEXUSPHERE_AGENT_ID` |
| `NEXUSPHERE_MODE`              | `record`    | `record`, or `decide` to let the ledger allow or deny each call                 |
| `NEXUSPHERE_AGENT_ID`          | (none)      | A fixed agent id for every call                                                 |
| `NEXUSPHERE_AGENT_CLAIM`       | `client_id` | The token claim that names the agent when no fixed id is set                    |
| `NEXUSPHERE_PRINCIPAL_CLAIM`   | `sub`       | The token claim that names the principal                                        |
| `NEXUSPHERE_DEFAULT_PRINCIPAL` | (none)      | The principal when the token has none                                           |

<p style="text-align: justify;">

Keep the key out of plain environment variables in production: encrypt it with a customer managed KMS key, or read
it from AWS Secrets Manager through the Lambda extension and set the variable at cold start.

</p>

## Identity

<p style="text-align: justify;">

The gateway has already validated the caller's JWT before the interceptor runs, so the interceptor reads its claims
without checking the signature again. A call without an agent or a principal is not recorded in `record` mode and is
denied with `UNKNOWN_IDENTITY` in `decide` mode. When the ledger cannot be reached, `record` mode logs the error and
lets the call through, while `decide` mode denies it with `LEDGER_UNAVAILABLE`.

</p>

| Evidence field  | Source                                                     |
|-----------------|------------------------------------------------------------|
| `agentId`       | `NEXUSPHERE_AGENT_ID`, or the agent claim of the token     |
| `principalId`   | The principal claim of the token, or the default principal |
| `target`        | `params.name` of the `tools/call`                          |
| `inputHash`     | SHA-256 of `params.arguments` as canonical JSON            |
| `outputHash`    | SHA-256 of the JSON-RPC `result` as canonical JSON         |
| `outcome`       | `FAILED` for a JSON-RPC error, `isError` or an HTTP error  |
| `correlationId` | The `Mcp-Session-Id` header                                |

## Test

<p style="text-align: justify;">

The tests feed the function the interceptor events in the shapes the AgentCore documentation specifies; they do not
call AWS.

</p>

Step 1. Run the tests; they must end with `OK`:

```shell
cd ledger/adapters/agentcore && python3 -m unittest -v
```

Step 2. Run them against a running ledger; the two `LiveLedger` tests must pass instead of being skipped:

```shell
cd ledger/adapters/agentcore && NEXUSPHERE_LEDGER_URL=http://localhost:8090 python3 -m unittest -v tests.test_live_ledger
```

##

**<p align="center">[Top](#nexusphere-ledger-for-amazon-bedrock-agentcore)</p>**
