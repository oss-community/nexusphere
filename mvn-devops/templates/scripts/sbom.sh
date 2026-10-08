#!/bin/sh
# Pipeline step of the security/syft module: writes the software bill of
# materials of the pushed image to target/sbom.spdx.json (SPDX) and
# target/sbom.cdx.json (CycloneDX), and prints a summary.
set -eu
scripts=$(dirname "$0")
syft=$(sh "$scripts/tool.sh" syft)
image=$(sh "$scripts/image-ref.sh")
if [ "${IMAGE_REGISTRY_INSECURE:-0}" = 1 ]; then
  export SYFT_REGISTRY_INSECURE_USE_HTTP=true
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
sh "$scripts/registry-auth.sh" "$work/docker"
export DOCKER_CONFIG="$work/docker"
mkdir -p target
"$syft" scan "registry:$image" --quiet \
  --output spdx-json=target/sbom.spdx.json --output cyclonedx-json=target/sbom.cdx.json
echo "SBOM of $image: target/sbom.spdx.json, target/sbom.cdx.json ($(grep -o '"SPDXID"' target/sbom.spdx.json | wc -l | tr -d ' ') elements)"
