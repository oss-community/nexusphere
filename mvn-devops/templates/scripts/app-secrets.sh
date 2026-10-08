#!/bin/sh
# Prints the secrets of the application in one environment as a JSON object
# of names and values:
#
#   - with the database module (DATABASE_ENGINE), the connection to the
#     environment's database at <database host>: SPRING_DATASOURCE_URL,
#     _USERNAME and _PASSWORD;
#   - with the monitoring module (METRICS_EXPOSURE), the actuator endpoints
#     the application exposes, so Prometheus can scrape it, and histograms of
#     the response times;
#   - with Vault (VAULT_ADDR), the KV (version 2) secret
#     $VAULT_KV_MOUNT/$VAULT_APP/<environment>, which wins over the above.
#     The (periodic) token is renewed, so it stays valid while the pipeline
#     uses it.
#
# Prints {} when there are none.
#
#   app-secrets.sh <environment> [database host]
set -eu

environment=$1
db_host=${2:-}
scripts=$(cd "$(dirname "$0")" && pwd)

json() { printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"; }

settings='{}'
if [ "${DATABASE_ENGINE:-}" = postgresql ] && [ -n "$db_host" ]; then
  case $environment in
    *[!a-z0-9]*) echo "app-secrets.sh: invalid environment '$environment'" >&2; exit 2 ;;
  esac
  key=$(printf '%s' "$environment" | tr '[:lower:]' '[:upper:]')
  password=''
  eval "password=\${DATABASE_${key}_PASSWORD:-}"
  settings="{\"SPRING_DATASOURCE_URL\": $(json "jdbc:postgresql://$db_host:5432/$DATABASE_NAME"),"
  settings="$settings \"SPRING_DATASOURCE_USERNAME\": $(json "$DATABASE_NAME"), \"SPRING_DATASOURCE_PASSWORD\": $(json "$password")}"
fi

if [ -n "${METRICS_EXPOSURE:-}" ]; then
  setting="\"MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE\": $(json "$METRICS_EXPOSURE"),"
  setting="$setting \"MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILESHISTOGRAM_HTTP_SERVER_REQUESTS\": \"true\""
  if [ "$settings" = '{}' ]; then settings="{$setting}"; else settings="${settings%\}}, $setting}"; fi
fi

if [ -z "${VAULT_ADDR:-}" ]; then
  printf '%s\n' "$settings"
  exit 0
fi
jq=$(command -v jq 2> /dev/null || sh "$scripts/tool.sh" jq)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

curl -s -o /dev/null -X POST -H "X-Vault-Token: $VAULT_TOKEN" "$VAULT_ADDR/v1/auth/token/renew-self" || true
path=${VAULT_KV_MOUNT:-secret}/data/$VAULT_APP/$environment
status=$(curl -s -o "$work/secret.json" -w '%{http_code}' -H "X-Vault-Token: $VAULT_TOKEN" "$VAULT_ADDR/v1/$path")
case $status in
  200) ;;
  404) echo '{}' > "$work/secret.json" ;;
  *)
    echo "app-secrets.sh: Vault answered HTTP $status for $path" >&2
    cat "$work/secret.json" >&2 2> /dev/null || true
    exit 1 ;;
esac
"$jq" -c --argjson base "$settings" '$base + (.data.data // {} | with_entries(.value |= tostring))' "$work/secret.json"
