# shellcheck shell=bash
# Container image pushed to the GitHub Container Registry (ghcr.io) with the
# GitHub user and token; the token needs the write:packages scope
# (docs/github-setup.md).  New packages are private; make one public under the
# package's settings, or give the machines that pull it a token with
# read:packages.

ghcr_repository() {
  local owner
  owner=$(value GITHUB_REPOSITORY); owner=${owner%%/*}
  printf 'ghcr.io/%s/%s' "$(printf '%s' "$owner" | tr '[:upper:]' '[:lower:]')" "$(value IMAGE_NAME "$PROJECT_NAME")"
}

module_secrets() {
  [[ $(github_host) == github.com ]] \
    || log_warn "GitHub Enterprise serves its container registry at containers.<host>; use the Docker registry module with that address."
  image_secrets
}

module_env() {
  image_env "$(ghcr_repository)" "$(ghcr_repository)" "$(value GITHUB_USERNAME)" "$(value GITHUB_TOKEN)"
}

module_stages() {
  image_stages 0 1
}

module_urls() {
  local owner
  owner=$(value GITHUB_REPOSITORY); owner=${owner%%/*}
  printf '  %-12s %s   (https://github.com/%s?tab=packages)\n' Image "$(ghcr_repository)" "$owner"
}
