# shellcheck shell=bash
# Nexus 3: replaces the generated admin password and accepts the community
# EULA.  With an existing server (NEXUS_SERVER_URL) it only checks the
# credentials you give it.  maven-releases and maven-snapshots are the default
# repositories.

module_secrets() {
  ask_server NEXUS Nexus https://nexus.example.com
  if server_external NEXUS; then
    ask_local NEXUS_USERNAME "Nexus user that may deploy" admin
    ask_secret NEXUS_PASSWORD "Password of $(value NEXUS_USERNAME)"
  else
    ask NEXUS_HOST_PORT "Nexus port on the Docker machine" 8084
    ask_secret NEXUS_ADMIN_PASSWORD "New Nexus admin password" "$(random_password)"
    log_dim "  Nexus Community Edition accepts uploads only after you accept its EULA:"
    log_dim "  https://links.sonatype.com/products/nxrm/ce-eula"
    # A licence is accepted by a person, also with a ready-made pipeline.
    DEVOPS_PRESET=0 ask NEXUS_ACCEPT_EULA "Accept the Nexus Community Edition EULA? yes/no" no
  fi
  ask NEXUS_SNAPSHOT_REPOSITORY "Nexus snapshot repository" maven-snapshots
  ask NEXUS_RELEASE_REPOSITORY "Nexus release repository" maven-releases
}

nexus_url() { server_url NEXUS "$(value NEXUS_HOST_PORT 8084)"; }

nexus_user() {
  if server_external NEXUS; then value NEXUS_USERNAME admin; else printf 'admin'; fi
}

nexus_password() {
  if server_external NEXUS; then value NEXUS_PASSWORD; else value NEXUS_ADMIN_PASSWORD; fi
}

nexus_api() {
  local auth=$1 method=$2 path=$3; shift 3
  curl -s -u "$auth" -X "$method" "$(nexus_url)/service/rest$path" "$@"
}

configure_existing() {
  local auth repo status repos
  auth="$(nexus_user):$(nexus_password)"
  repos=$(mktemp)
  status=$(nexus_api "$auth" GET /v1/repositories -o "$repos" -w '%{http_code}')
  [[ $status == 401 ]] && { rm -f "$repos"; die "$(nexus_url) rejects $(nexus_user) with NEXUS_PASSWORD. Fix it with '$DEVOPS_CMD secrets --reconfigure'."; }
  log_ok "Logged in to $(nexus_url) as $(nexus_user)"
  for repo in "$(value NEXUS_SNAPSHOT_REPOSITORY maven-snapshots)" "$(value NEXUS_RELEASE_REPOSITORY maven-releases)"; do
    if jq -e --arg r "$repo" 'any(.[]; .name == $r)' "$repos" > /dev/null 2>&1; then
      log_ok "Repository $repo exists"
    else
      log_warn "Repository $repo was not found on $(nexus_url) (or $(nexus_user) cannot see it)."
    fi
  done
  rm -f "$repos"
}

module_configure() {
  local password initial status eula
  wait_http "$(nexus_url)/service/rest/v1/status" 600 '^200$' \
    || die "Nexus at $(nexus_url) is not up. Check '$DEVOPS_CMD logs nexus' or the server."
  if server_external NEXUS; then
    configure_existing
    return
  fi
  password=$(require_value NEXUS_ADMIN_PASSWORD)

  initial=$(compose exec -T nexus cat /nexus-data/admin.password 2>/dev/null || true)
  if [[ -n $initial ]]; then
    status=$(nexus_api "admin:$initial" PUT /v1/security/users/admin/change-password \
      -H 'Content-Type: text/plain' --data-raw "$password" -o /dev/null -w '%{http_code}')
    [[ $status == 204 ]] || die "Could not change the Nexus admin password (HTTP $status)."
    compose exec -T nexus rm -f /nexus-data/admin.password > /dev/null 2>&1 || true
    log_ok "Replaced the generated admin password"
  fi

  status=$(nexus_api "admin:$password" GET /v1/status/check -o /dev/null -w '%{http_code}')
  [[ $status == 200 ]] || die "Nexus rejects admin with NEXUS_ADMIN_PASSWORD (HTTP $status)."

  # Nexus Community Edition (3.77+) refuses uploads until the EULA is accepted.
  eula=$(nexus_api "admin:$password" GET /v1/system/eula 2>/dev/null || true)
  if [[ $(jq -r '.accepted' <<< "$eula" 2>/dev/null) == false ]]; then
    if [[ $(value NEXUS_ACCEPT_EULA no) =~ ^[Yy] ]]; then
      status=$(nexus_api "admin:$password" POST /v1/system/eula -H 'Content-Type: application/json' \
        --data "$(jq -c '.accepted = true' <<< "$eula")" -o /dev/null -w '%{http_code}')
      [[ $status == 204 || $status == 200 ]] || die "Could not accept the Nexus EULA (HTTP $status)."
      log_ok "Accepted the Nexus Community Edition EULA"
    else
      log_warn "The Nexus EULA is not accepted, so deploys to Nexus will fail. Accept it in the UI,"
      log_warn "or set NEXUS_ACCEPT_EULA with '$DEVOPS_CMD secrets --reconfigure' and run configure again."
    fi
  fi
}

module_env() {
  local base
  base=$(server_pipeline_url NEXUS nexus 8081 "$(value NEXUS_HOST_PORT 8084)")
  pipeline_var NEXUS_ARTIFACTORY_USERNAME "$(nexus_user)"
  pipeline_secret NEXUS_ARTIFACTORY_PASSWORD "$(nexus_password)"
  pipeline_var NEXUS_ARTIFACTORY_HOST_URL "$base"
  pipeline_var NEXUS_ARTIFACTORY_SNAPSHOT_URL "$base/repository/$(value NEXUS_SNAPSHOT_REPOSITORY maven-snapshots)/"
  pipeline_var NEXUS_ARTIFACTORY_RELEASE_URL "$base/repository/$(value NEXUS_RELEASE_REPOSITORY maven-releases)/"
}

module_stages() {
  stage 72 cd deploy-nexus "$(mvn_deploy_args nexus-snapshots '$NEXUS_ARTIFACTORY_SNAPSHOT_URL' nexus-releases '$NEXUS_ARTIFACTORY_RELEASE_URL')"
}

module_urls() {
  if server_external NEXUS; then
    printf '  %-12s %s   (%s)\n' Nexus "$(nexus_url)" "$(nexus_user)"
  else
    printf '  %-12s %s   (admin / devops.sh get NEXUS_ADMIN_PASSWORD)\n' Nexus "$(nexus_url)"
  fi
}
