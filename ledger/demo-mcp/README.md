# <p align="center">Nexusphere Ledger Demo MCP</p>

<p align="center">A small MCP server and a script that show the ledger end to end.</p>

## <p align="center">Table of Content</p>

* [Purpose](#purpose)
* [Responsibilities](#responsibilities)
* [Dependencies](#dependencies)

## Purpose

<p style="text-align: justify;">

The ledger is easiest to understand by watching it stop and record a real tool call. This module is a toy MCP server
with harmless tools and the script that walks through the whole flow against the compose stack.

</p>

## Responsibilities

* Serve the MCP tools `read_file`, `send_email` and `delete_file` over Streamable HTTP on port 8091
* `demo.sh`: register an agent, ask `alice` for a grant and approve it with a Keycloak sign-in, call tools through the gateway, see an allowed and a denied call,
  export a package and verify it, then show that a tampered package fails

## Dependencies

| Depends on | Used by |
|------------|---------|
| none       | none    |

How to run it is in [Demo](../README.md#demo).

##

**<p align="center">[Top](#nexusphere-ledger-demo-mcp)</p>**
