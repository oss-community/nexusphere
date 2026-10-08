# shellcheck shell=bash
# JFrog Artifactory OSS: replaces the default admin password, tries to create
# the Maven repositories and fetches the encrypted password the
# artifactory-maven-plugin uses.  With an existing server (JFROG_SERVER_URL)
# it only checks the credentials and repositories.

module_secrets() {
  local prefix
  ask_server JFROG Artifactory https://acme.jfrog.io/artifactory
  ask JFROG_ARTIFACTORY_REPOSITORY_PREFIX "Repository prefix (<prefix>-libs-release-local)" "$(printf '%s' "$PROJECT_NAME" | cut -d- -f1)"
  prefix=$(value JFROG_ARTIFACTORY_REPOSITORY_PREFIX)
  ask JFROG_RELEASE_REPOSITORY "Artifactory release repository" "$prefix-libs-release-local"
  ask JFROG_SNAPSHOT_REPOSITORY "Artifactory snapshot repository" "$prefix-libs-snapshot-local"
  if server_external JFROG; then
    ask_local JFROG_USERNAME "Artifactory user that may deploy" admin
    ask_secret JFROG_PASSWORD "Password, API key or identity token of $(value JFROG_USERNAME)"
    return
  fi
  ask JFROG_HOST_PORT "Artifactory port on the Docker machine" 8082
  ask JFROG_DB "Artifactory database name" artifactory
  ask JFROG_DB_USER "Artifactory database user" artifactory
  ask_secret JFROG_DB_PASSWORD "Artifactory database password" "$(random_password)"
  ask_secret JFROG_ADMIN_PASSWORD "New Artifactory admin password" "$(random_password)"
}

# Base URL ending in /artifactory.
jfrog_url() {
  if server_external JFROG; then value JFROG_SERVER_URL; else host_url "$(value JFROG_HOST_PORT 8082)" /artifactory; fi
}

jfrog_api() {
  local auth=$1 method=$2 path=$3; shift 3
  curl -s -u "$auth" -X "$method" "$(jfrog_url)/api$path" "$@"
}

jfrog_repositories() {
  printf '%s %s\n' release "$(value JFROG_RELEASE_REPOSITORY "$(value JFROG_ARTIFACTORY_REPOSITORY_PREFIX)-libs-release-local")"
  printf '%s %s\n' snapshot "$(value JFROG_SNAPSHOT_REPOSITORY "$(value JFROG_ARTIFACTORY_REPOSITORY_PREFIX)-libs-snapshot-local")"
}

configure_existing() {
  local auth kind key status encrypted
  auth="$(value JFROG_USERNAME admin):$(require_value JFROG_PASSWORD)"
  status=$(jfrog_api "$auth" GET /repositories -o /dev/null -w '%{http_code}')
  [[ $status == 401 ]] && die "$(jfrog_url) rejects $(value JFROG_USERNAME admin) with JFROG_PASSWORD. Fix it with '$DEVOPS_CMD secrets --reconfigure'."
  log_ok "Logged in to $(jfrog_url) as $(value JFROG_USERNAME admin)"
  while read -r kind key; do
    if [[ $(jfrog_api "$auth" GET "/repositories/$key" -o /dev/null -w '%{http_code}') == 200 ]]; then
      log_ok "Repository $key exists"
    else
      log_warn "The $kind repository $key was not found on $(jfrog_url)."
    fi
  done < <(jfrog_repositories)
  # Prefer the encrypted password; tokens and API keys are used as they are.
  encrypted=$(jfrog_api "$auth" GET /security/encryptedPassword -f 2>/dev/null || true)
  set_value JFROG_ARTIFACTORY_ENCRYPTED_PASSWORD "${encrypted:-$(value JFROG_PASSWORD)}" secret
}

jfrog_auth_ok() {
  [[ $(jfrog_api "$1" GET /security/encryptedPassword -o /dev/null -w '%{http_code}') == 200 ]]
}

module_configure() {
  local password prefix key kind status encrypted body manual=0
  wait_http "$(jfrog_url)/api/system/ping" 900 '^200$' \
    || die "Artifactory at $(jfrog_url) is not up. Check '$DEVOPS_CMD logs jfrog' or the server."
  if server_external JFROG; then
    configure_existing
    return
  fi
  password=$(require_value JFROG_ADMIN_PASSWORD)
  prefix=$(value JFROG_ARTIFACTORY_REPOSITORY_PREFIX)

  if jfrog_auth_ok "admin:password"; then
    body=$(jq -nc --arg p "$password" '{userName: "admin", oldPassword: "password", newPassword1: $p, newPassword2: $p}')
    status=$(jfrog_api admin:password POST /security/users/authorization/changePassword \
      -H 'Content-Type: application/json' --data "$body" -o /dev/null -w '%{http_code}')
    if [[ $status == 200 ]]; then
      log_ok "Replaced the default admin password"
    else
      log_warn "Could not change the default password (HTTP $status). Log in with admin/password and set it to JFROG_ADMIN_PASSWORD."
    fi
  fi
  jfrog_auth_ok "admin:$password" || die "Artifactory rejects admin with JFROG_ADMIN_PASSWORD. Change it in the UI or run '$DEVOPS_CMD secrets --reconfigure'."

  while read -r kind key; do
    if [[ $(jfrog_api "admin:$password" GET "/repositories/$key" -o /dev/null -w '%{http_code}') == 200 ]]; then
      log_dim "  repository $key exists"
      continue
    fi
    body=$(jq -nc --arg k "$key" --arg kind "$kind" \
      '{key: $k, rclass: "local", packageType: "maven", handleReleases: ($kind == "release"), handleSnapshots: ($kind == "snapshot")}')
    status=$(jfrog_api "admin:$password" PUT "/repositories/$key" -H 'Content-Type: application/json' --data "$body" -o /dev/null -w '%{http_code}')
    if [[ $status == 200 ]]; then log_ok "Created repository $key"; else manual=1; fi
  done < <(jfrog_repositories)
  if (( manual )); then
    log_warn "Artifactory OSS does not allow creating repositories through the API."
    log_warn "Open $(jfrog_url), choose 'Quick Setup' > Maven and use the prefix '$prefix'."
  fi

  encrypted=$(jfrog_api "admin:$password" GET /security/encryptedPassword)
  [[ -n $encrypted ]] || die "Could not fetch the encrypted admin password."
  set_value JFROG_ARTIFACTORY_ENCRYPTED_PASSWORD "$encrypted" secret
  log_ok "Stored the encrypted password for the pipeline"
}

module_env() {
  local context prefix user=admin kind key
  if server_external JFROG; then
    context=$(value JFROG_SERVER_URL); user=$(value JFROG_USERNAME admin)
  else
    context=$(pipeline_url jfrog 8082 "$(value JFROG_HOST_PORT 8082)" /artifactory)
  fi
  prefix=$(value JFROG_ARTIFACTORY_REPOSITORY_PREFIX)
  pipeline_var JFROG_ARTIFACTORY_USERNAME "$user"
  pipeline_secret JFROG_ARTIFACTORY_ENCRYPTED_PASSWORD "$(value JFROG_ARTIFACTORY_ENCRYPTED_PASSWORD "$(value JFROG_PASSWORD)")"
  pipeline_var JFROG_ARTIFACTORY_CONTEXT_URL "$context"
  pipeline_var JFROG_ARTIFACTORY_REPOSITORY_PREFIX "$prefix"
  while read -r kind key; do
    if [[ $kind == release ]]; then
      pipeline_var JFROG_ARTIFACTORY_RELEASE_URL "$context/$key/"
    else
      pipeline_var JFROG_ARTIFACTORY_SNAPSHOT_URL "$context/$key/"
    fi
  done < <(jfrog_repositories)
}

module_stages() {
  stage 70 cd deploy-jfrog "$(mvn_deploy_args jfrog-snapshots '$JFROG_ARTIFACTORY_SNAPSHOT_URL' jfrog-releases '$JFROG_ARTIFACTORY_RELEASE_URL')"
}

module_urls() {
  if server_external JFROG; then
    printf '  %-12s %s   (%s)\n' Artifactory "$(jfrog_url)" "$(value JFROG_USERNAME admin)"
  else
    printf '  %-12s %s   (admin / devops.sh get JFROG_ADMIN_PASSWORD)\n' Artifactory "$(jfrog_url)"
  fi
}
