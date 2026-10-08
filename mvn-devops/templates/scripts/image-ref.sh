#!/bin/sh
# Prints the reference of the image built from the current commit: by digest
# when the image stage of this run recorded it, else by the commit tag.
#   image-ref.sh [repository]     (default $IMAGE_REPOSITORY)
set -eu
repository=${1:-$IMAGE_REPOSITORY}
if [ -s "${IMAGE_DIGEST_FILE:-}" ]; then
  printf '%s@%s\n' "$repository" "$(tr -d '[:space:]' < "$IMAGE_DIGEST_FILE")"
else
  printf '%s:%s\n' "$repository" "$(git rev-parse --short=12 HEAD)"
fi
