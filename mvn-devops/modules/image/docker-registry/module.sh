# shellcheck shell=bash
# Container image pushed to a Docker registry: the Distribution registry in
# Docker, or an existing registry given by its address (REGISTRY_SERVER_URL),
# such as https://docker.io, https://harbor.example.com or a Docker repository
# of Nexus or Artifactory.

module_secrets() {
  ask_server REGISTRY "Docker registry" https://harbor.example.com
  if server_external REGISTRY; then
    ask_local REGISTRY_USERNAME "Registry user that may push"
    ask_secret REGISTRY_PASSWORD "Password or access token of $(value REGISTRY_USERNAME)"
  else
    ask REGISTRY_HOST_PORT "Registry port on the Docker machine" 5000
  fi
  image_secrets
  if server_external REGISTRY; then
    log_dim "  The image is $(registry_host)/$(value IMAGE_NAME); include the namespace in the name"
    log_dim "  where the registry needs one, e.g. acme/$(value IMAGE_NAME) on Docker Hub."
  fi
}

# Registry address without scheme, as image references use it.
registry_host() {
  local url
  url=$(value REGISTRY_SERVER_URL)
  url=${url#*://}
  printf '%s' "${url%%/*}"
}

module_configure() {
  local url status
  if server_external REGISTRY; then
    url=$(value REGISTRY_SERVER_URL)
    [[ $(registry_host) == docker.io ]] && url=https://registry-1.docker.io
    status=$(curl -s -o /dev/null -w '%{http_code}' -u "$(value REGISTRY_USERNAME):$(value REGISTRY_PASSWORD)" "$url/v2/")
    case $status in
      200) log_ok "Logged in to $(registry_host) as $(value REGISTRY_USERNAME)" ;;
      # Token registries (Docker Hub, GHCR, Harbor) answer 401 to basic auth
      # here and check the credentials when Jib asks for a token.
      401) log_dim "  $(registry_host) checks the credentials on the first push" ;;
      *) log_warn "$(registry_host) answered HTTP $status at $url/v2/" ;;
    esac
    return
  fi
  wait_http "$(host_url "$(value REGISTRY_HOST_PORT 5000)" /v2/)" 120 '^200$' \
    || die "The registry did not start. Check '$DEVOPS_CMD logs registry'."
  log_ok "Registry is up"
}

module_env() {
  local port image
  image=$(value IMAGE_NAME "$PROJECT_NAME")
  if server_external REGISTRY; then
    image_env "$(registry_host)/$image" "$(registry_host)/$image" "$(value REGISTRY_USERNAME)" "$(value REGISTRY_PASSWORD)"
    return
  fi
  port=$(value REGISTRY_HOST_PORT 5000)
  if [[ ${DEVOPS_RUNS_IN:-host} == docker ]]; then
    image_env "registry:5000/$image" "localhost:$port/$image" '' '' 1
  else
    image_env "$(devops_host):$port/$image" "localhost:$port/$image" '' '' 1
  fi
}

module_stages() {
  if server_external REGISTRY; then image_stages 0 1; else image_stages 1 0; fi
}

module_urls() {
  if server_external REGISTRY; then
    printf '  %-12s %s/%s\n' Image "$(registry_host)" "$(value IMAGE_NAME)"
  else
    printf '  %-12s %s   (tags: %s)\n' Registry "$(host_url "$(value REGISTRY_HOST_PORT 5000)" /v2/_catalog)" \
      "$(host_url "$(value REGISTRY_HOST_PORT 5000)" "/v2/$(value IMAGE_NAME)/tags/list")"
  fi
}
