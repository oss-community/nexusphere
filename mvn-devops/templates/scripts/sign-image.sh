#!/bin/sh
# Pipeline step of the security/cosign module: signs the pushed image with
# the project's Cosign key, and attests its SBOM when the syft step wrote one.
# Signatures are stored in the registry next to the image; nothing is sent to
# the public Sigstore services.
set -eu
scripts=$(dirname "$0")
cosign=$(sh "$scripts/tool.sh" cosign)
image=$(sh "$scripts/image-ref.sh")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '%s' "$COSIGN_KEY_B64" | base64 -d > "$work/cosign.key"
# A signing config without transparency log and timestamp services.
printf '%s' '{"mediaType":"application/vnd.dev.sigstore.signingconfig.v0.2+json","rekorTlogConfig":{},"tsaConfig":{}}' \
  > "$work/signing-config.json"
set -- --key "$work/cosign.key" --signing-config "$work/signing-config.json" --yes
if [ "${IMAGE_REGISTRY_INSECURE:-0}" = 1 ]; then set -- "$@" --allow-http-registry --allow-insecure-registry; fi
sh "$scripts/registry-auth.sh" "$work/docker"
export DOCKER_CONFIG="$work/docker"
export COSIGN_PASSWORD
"$cosign" sign "$@" "$image"
echo "Signed $image"
if [ -f target/sbom.spdx.json ]; then
  "$cosign" attest "$@" --type spdxjson --predicate target/sbom.spdx.json "$image"
  echo "Attested the SBOM of $image"
fi
