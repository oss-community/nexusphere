#!/usr/bin/env bash
set -euo pipefail

LEDGER_URL="${LEDGER_URL:-http://localhost:8090}"
OPERATOR_KEY="${LEDGER_API_KEY:-nexusphere-ledger-development-key-change-me}"
VERIFIER_JAR="${VERIFIER_JAR:-$(dirname "$0")/../verifier/target/verifier-1.0.0-SNAPSHOT-exec.jar}"
WORK_DIR="${WORK_DIR:-$(mktemp -d)}"
mkdir -p "${WORK_DIR}"
AGENT_ID="invoice-agent-$(date +%s)"
PRINCIPAL="alice"

step() { printf '\n== %s\n' "$1"; }
operator() { curl -sS -H "Authorization: Bearer ${OPERATOR_KEY}" -H "Content-Type: application/json" "$@"; }
tool() {
  curl -sS -D "${WORK_DIR}/headers" -H "Authorization: Bearer ${AGENT_KEY}" -H "X-Ledger-Principal: ${PRINCIPAL}" \
    -H "Content-Type: application/json" "${LEDGER_URL}/mcp/demo" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$1,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}"
  echo
}

step "Register the agent ${AGENT_ID} owned by acme"
AGENT_KEY=$(operator -X POST "${LEDGER_URL}/api/v1/agents" \
  -d "{\"agentId\":\"${AGENT_ID}\",\"name\":\"Invoice agent\",\"ownerId\":\"acme\"}" | jq -r '.apiKey')
echo "agent key: ${AGENT_KEY:0:10}..."

step "${PRINCIPAL} grants read_file and send_email on the demo server, at most 5 uses, for one hour"
EXPIRES=$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+1H +%Y-%m-%dT%H:%M:%SZ)
operator -X POST "${LEDGER_URL}/api/v1/grants" -d "{\"principalId\":\"${PRINCIPAL}\",\"agentId\":\"${AGENT_ID}\",\
\"actions\":[\"tools/call\"],\"targets\":[\"demo/read_file\",\"demo/send_email\"],\"expiresAt\":\"${EXPIRES}\",\
\"maxUses\":5,\"reason\":\"Monthly invoicing\"}" | jq '{id, status, targets, maxUses, termsHash}'

step "The agent reads the invoice through the gateway (allowed)"
tool 1 read_file '{"path":"/invoices/2026-10.txt"}' | jq -c '.result.content[0].text'

step "The agent emails the customer (allowed)"
tool 2 send_email '{"to":"customer@example.com","subject":"Invoice 2026-10"}' | jq -c '.result.content[0].text'

step "The agent tries to delete the invoice (denied, never reaches the server)"
tool 3 delete_file '{"path":"/invoices/2026-10.txt"}' | jq -c '.error | {code, message, reasonCode: .data.reasonCode}'

step "Evidence recorded for ${AGENT_ID}"
operator "${LEDGER_URL}/api/v1/evidence?agentId=${AGENT_ID}" \
  | jq -r '.items[] | "\(.sequence)  \(.action)  \(.target // "-")  \(.decision // "-")  \(.outcome)"'

step "Export an evidence package for ${AGENT_ID}"
operator -X POST "${LEDGER_URL}/api/v1/packages" -d "{\"agentId\":\"${AGENT_ID}\"}" -o "${WORK_DIR}/package.json"
jq '{format, disclosed, links: (.links | length), checkpoint: .checkpoint.sequence}' "${WORK_DIR}/package.json"

step "Verify the package offline with the ledger's published key"
PUBLIC_KEY=$(operator "${LEDGER_URL}/api/v1/keys" | jq -r '.[0].publicKey')
java -jar "${VERIFIER_JAR}" --public-key "${PUBLIC_KEY}" "${WORK_DIR}/package.json"

step "Change one disclosed entry and verify again"
jq '(.links[] | select(.entry != null) | .entry.target) |= "demo/delete_file"' "${WORK_DIR}/package.json" \
  > "${WORK_DIR}/tampered.json"
java -jar "${VERIFIER_JAR}" --public-key "${PUBLIC_KEY}" "${WORK_DIR}/tampered.json" || echo "exit code $?"
