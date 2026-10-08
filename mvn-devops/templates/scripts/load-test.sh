#!/bin/sh
# Load test of an environment with k6 (stage script of the loadtest/k6
# module): the project's script (LOAD_TEST_SCRIPT) or load-test.js against
# LOAD_TEST_URL.  The stage fails when a threshold of the script fails, so a
# release that is too slow or fails requests does not reach production.  The
# summary is written to target/load-test.json; with Prometheus
# (LOAD_TEST_PROMETHEUS_URL) the metrics go there too, tagged with the
# environment.
#
#   load-test.sh <environment>
set -eu

environment=$1
scripts=$(cd "$(dirname "$0")" && pwd)
k6=$(sh "$scripts/tool.sh" k6)
script=$scripts/load-test.js
if [ -n "${LOAD_TEST_SCRIPT:-}" ]; then
  [ -f "$LOAD_TEST_SCRIPT" ] || { echo "load-test.sh: $LOAD_TEST_SCRIPT does not exist" >&2; exit 1; }
  script=$LOAD_TEST_SCRIPT
fi
set -- --tag "environment=$environment" --summary-export target/load-test.json
if [ -n "${LOAD_TEST_PROMETHEUS_URL:-}" ]; then
  export K6_PROMETHEUS_RW_SERVER_URL="$LOAD_TEST_PROMETHEUS_URL/api/v1/write"
  export K6_PROMETHEUS_RW_TREND_STATS='p(95),avg,max'
  set -- "$@" --out experimental-prometheus-rw
fi
mkdir -p target

# The application may still be starting after the deployment.
i=0
until curl -fsS -o /dev/null "$LOAD_TEST_URL${LOAD_TEST_HEALTH_PATH:-/actuator/health}" 2> /dev/null; do
  i=$((i + 1))
  [ $i -lt 30 ] || { echo "load-test.sh: $LOAD_TEST_URL does not answer" >&2; exit 1; }
  sleep 2
done

echo "Load test of $environment ($LOAD_TEST_URL) with $(basename "$script")"
K6_NO_USAGE_REPORT=true "$k6" run "$@" \
  -e "BASE_URL=$LOAD_TEST_URL" -e "PATHS=${LOAD_TEST_PATHS:-/actuator/health}" \
  -e "VUS=${LOAD_TEST_VUS:-10}" -e "DURATION=${LOAD_TEST_DURATION:-30s}" \
  -e "P95_MS=${LOAD_TEST_P95_MS:-500}" -e "MAX_ERROR_RATE=${LOAD_TEST_MAX_ERROR_RATE:-0.01}" \
  "$script"
