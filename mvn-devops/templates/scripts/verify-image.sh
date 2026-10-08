#!/bin/sh
# Pipeline step of the security/cosign module: checks that the image of the
# current commit carries a signature of the project's Cosign key before it
# goes to an environment that needs approval.
set -eu
scripts=$(dirname "$0")
cosign=$(sh "$scripts/tool.sh" cosign)
image=$(sh "$scripts/image-ref.sh")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '%s' "$COSIGN_PUBLIC_KEY_B64" | base64 -d > "$work/cosign.pub"
set -- --key "$work/cosign.pub" --insecure-ignore-tlog=true --output text
if [ "${IMAGE_REGISTRY_INSECURE:-0}" = 1 ]; then set -- "$@" --allow-http-registry --allow-insecure-registry; fi
sh "$scripts/registry-auth.sh" "$work/docker"
export DOCKER_CONFIG="$work/docker"
"$cosign" verify "$@" "$image" > /dev/null
echo "$image is signed with the project's key"
