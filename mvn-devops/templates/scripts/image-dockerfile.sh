#!/usr/bin/env bash
# Pipeline step: builds the image from the project's Dockerfile and pushes it.
# Needs Docker where the pipeline runs.  Usage: image-dockerfile.sh [module]
# The Dockerfile of the module is used when it has one, else the root's.
# Writes the digest to <module>/target/jib-image.digest, where Jib writes it.
set -euo pipefail

module=${1:-.}
tag=$(git rev-parse --short=12 HEAD)
dockerfile=Dockerfile
[[ -f "$module/Dockerfile" ]] && dockerfile="$module/Dockerfile"

if [[ -n ${IMAGE_REGISTRY_PASSWORD:-} ]]; then
  registry=${IMAGE_REPOSITORY%%/*}
  [[ $registry == *.* || $registry == *:* || $registry == localhost ]] || registry=docker.io
  printf '%s' "$IMAGE_REGISTRY_PASSWORD" \
    | docker login --username "$IMAGE_REGISTRY_USERNAME" --password-stdin "$registry"
fi
docker build --file "$dockerfile" --tag "$IMAGE_REPOSITORY:$tag" --tag "$IMAGE_REPOSITORY:latest" "$module"
docker push "$IMAGE_REPOSITORY:$tag"
docker push "$IMAGE_REPOSITORY:latest"
mkdir -p "$module/target"
digest=$(docker inspect --format '{{index .RepoDigests 0}}' "$IMAGE_REPOSITORY:$tag")
printf '%s\n' "${digest#*@}" > "$module/target/jib-image.digest"
