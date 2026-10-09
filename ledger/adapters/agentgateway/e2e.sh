#!/usr/bin/env bash
set -euo pipefail

GATEWAY="${AGENTGATEWAY_URL:-http://localhost:3000/mcp}"
LEDGER="${NEXUSPHERE_LEDGER_URL:-http://localhost:8090}"
KEY="${NEXUSPHERE_LEDGER_API_KEY:-nexusphere-ledger-development-key-change-me}"
AGENT="agentgateway-e2e-$(date +%s)-$RANDOM"
HEADERS=(-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream"
  -H "x-nexusphere-agent: $AGENT" -H "x-nexusphere-principal: alice")

mcp() {
  curl -sS -X POST "$GATEWAY" "${HEADERS[@]}" ${SESSION:+-H "Mcp-Session-Id: $SESSION"} -d "$1"
}

SESSION="$(curl -sS -D - -o /dev/null -X POST "$GATEWAY" "${HEADERS[@]}" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"e2e","version":"1.0.0"}}}' \
  | tr -d '\r' | awk 'tolower($1) == "mcp-session-id:" {print $2}')"
[ -n "$SESSION" ] || { echo "Result: FAILED, no MCP session from $GATEWAY"; exit 1; }
mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
mcp '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/reports/q3.txt"}}}' > /dev/null
mcp '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}' > /dev/null

for _ in $(seq 1 30); do
  ENTRIES="$(curl -sS "$LEDGER/api/v1/evidence?agentId=$AGENT" -H "Authorization: Bearer $KEY" \
    | jq -c '[.items[] | select(.action == "tools/call") | {target, principalId, outcome}] | sort_by(.target)')"
  [ "$(jq length <<< "$ENTRIES")" -ge 2 ] && break
  sleep 1
done
echo "Evidence for $AGENT: $ENTRIES"
EXPECTED='[{"target":"no_such_tool","principalId":"alice","outcome":"FAILED"},{"target":"read_file","principalId":"alice","outcome":"SUCCEEDED"}]'
if [ "$ENTRIES" = "$EXPECTED" ]; then
  echo "Result: PASSED"
else
  echo "Result: FAILED, expected $EXPECTED"
  exit 1
fi
