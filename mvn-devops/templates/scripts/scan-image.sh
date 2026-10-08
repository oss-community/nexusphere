#!/bin/sh
# Pipeline step of the security/trivy module: scans the pushed image (the
# operating system packages and the jars in it) for known vulnerabilities.
# Writes target/trivy-report.json, prints the findings of TRIVY_SEVERITY and
# fails when there are fixable ones of TRIVY_FAIL_ON (empty: never fails).
set -eu
scripts=$(dirname "$0")
trivy=$(sh "$scripts/tool.sh" trivy)
image=$(sh "$scripts/image-ref.sh")
cache=${DEVOPS_TOOLS:-$HOME/.cache/mvn-devops/tools}/trivy-cache
if [ "${IMAGE_REGISTRY_INSECURE:-0}" = 1 ]; then
  export TRIVY_INSECURE=true
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
sh "$scripts/registry-auth.sh" "$work/docker"
export DOCKER_CONFIG="$work/docker"
mkdir -p target
echo "Scanning $image"
"$trivy" image --quiet --cache-dir "$cache" --image-src remote --scanners vuln \
  --format json --output target/trivy-report.json "$image"
"$trivy" convert --quiet --format table --severity "${TRIVY_SEVERITY:-HIGH,CRITICAL}" target/trivy-report.json
if [ -n "${TRIVY_FAIL_ON:-}" ]; then
  # Again from the cache, counting only vulnerabilities that have a fix.
  "$trivy" image --quiet --cache-dir "$cache" --image-src remote --scanners vuln --skip-db-update \
    --severity "$TRIVY_FAIL_ON" --ignore-unfixed --exit-code 1 --format table --output "$work/fail.txt" "$image" \
    || { cat "$work/fail.txt"; echo "The image has fixable vulnerabilities of severity $TRIVY_FAIL_ON" >&2; exit 1; }
fi
echo "No fixable vulnerabilities of severity ${TRIVY_FAIL_ON:-(none checked)}"
