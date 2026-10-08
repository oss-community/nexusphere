#!/bin/sh
# Writes a Docker config with the registry credentials of the pipeline
# (IMAGE_REGISTRY_USERNAME and _PASSWORD) into <dir>, for tools that read
# DOCKER_CONFIG, so the password never appears on a command line.
#   registry-auth.sh <dir>
set -eu
mkdir -p "$1"
registry=${IMAGE_REPOSITORY%%/*}
case $registry in *.* | *:* | localhost) ;; *) registry=https://index.docker.io/v1/ ;; esac
auth=$(printf '%s:%s' "${IMAGE_REGISTRY_USERNAME:-}" "${IMAGE_REGISTRY_PASSWORD:-}" | base64 | tr -d '\n')
if [ -n "${IMAGE_REGISTRY_PASSWORD:-}" ]; then
  printf '{"auths":{"%s":{"auth":"%s"}}}\n' "$registry" "$auth" > "$1/config.json"
else
  printf '{}\n' > "$1/config.json"
fi
chmod 600 "$1/config.json"
