# shellcheck shell=bash
# SonarQube: starts the server, replaces the default admin password and
# creates the analysis token the pipeline uses.  With an existing server
# (SONAR_SERVER_URL) it only checks the token you give it.

module_secrets() {
  ask_server SONAR SonarQube https://sonar.example.com
  if server_external SONAR; then
    log_dim "  Create a token under My Account > Security on the server."
    ask_secret SONAR_TOKEN "SonarQube analysis token"
    return
  fi
  ask SONAR_HOST_PORT "SonarQube port on the Docker machine" 9000
  ask SONAR_DB "SonarQube database name" sonar
  ask SONAR_DB_USER "SonarQube database user" sonar
  ask_secret SONAR_DB_PASSWORD "SonarQube database password" "$(random_password)"
  ask_secret SONAR_ADMIN_PASSWORD "New SonarQube admin password" "$(random_password)"
}

sonar_url() { server_url SONAR "$(value SONAR_HOST_PORT 9000)"; }

sonar_api() {
  local auth=$1 method=$2 path=$3; shift 3
  curl -s -u "$auth" -X "$method" "$(sonar_url)$path" "$@"
}

sonar_valid() {
  [[ $(sonar_api "$1" GET /api/authentication/validate | jq -r '.valid' 2>/dev/null) == true ]]
}

sonar_wait() {
  local base start
  base=$(sonar_url)
  printf '  waiting for SonarQube at %s ' "$base"
  start=$(date +%s)
  until [[ $(curl -s "$base/api/system/status" | jq -r '.status' 2>/dev/null) == UP ]]; do
    (( $(date +%s) - start > 600 )) && { printf ' timeout\n'; die "SonarQube at $base is not up. Check '$DEVOPS_CMD logs sonarqube' or the server."; }
    printf '.'; sleep 5
  done
  printf ' up\n'
}

module_configure() {
  local admin_password status token
  sonar_wait
  if server_external SONAR; then
    sonar_valid "$(require_value SONAR_TOKEN):" \
      || die "$(sonar_url) rejects SONAR_TOKEN. Fix it with '$DEVOPS_CMD secrets --reconfigure'."
    log_ok "SONAR_TOKEN is valid"
    return
  fi
  admin_password=$(require_value SONAR_ADMIN_PASSWORD)

  if sonar_valid "admin:admin"; then
    status=$(sonar_api admin:admin POST /api/users/change_password -o /dev/null -w '%{http_code}' \
      --data-urlencode login=admin --data-urlencode previousPassword=admin \
      --data-urlencode "password=$admin_password")
    [[ $status == 204 ]] || die "Could not change the SonarQube admin password (HTTP $status)."
    log_ok "Replaced the default admin password"
  elif ! sonar_valid "admin:$admin_password"; then
    die "SonarQube rejects admin with SONAR_ADMIN_PASSWORD. Fix it with '$DEVOPS_CMD secrets --reconfigure'."
  fi

  sonar_api "admin:$admin_password" POST /api/user_tokens/revoke -o /dev/null --data-urlencode name=mvn-devops
  token=$(sonar_api "admin:$admin_password" POST /api/user_tokens/generate --data-urlencode name=mvn-devops | jq -r '.token // empty')
  [[ -n $token ]] || die "Could not create a SonarQube token."
  set_value SONAR_TOKEN "$token" secret
  log_ok "Created analysis token 'mvn-devops'"
}

module_env() {
  pipeline_var SONAR_URL "$(server_pipeline_url SONAR sonarqube 9000 "$(value SONAR_HOST_PORT 9000)")"
  pipeline_secret SONAR_TOKEN "$(value SONAR_TOKEN)"
}

module_stages() {
  stage 45 ci sonar "$(mvn_sonar) -Dsonar.host.url=\$SONAR_URL -Dsonar.token=\$SONAR_TOKEN"
}

module_urls() {
  if server_external SONAR; then
    printf '  %-12s %s\n' SonarQube "$(sonar_url)"
  else
    printf '  %-12s %s   (admin / devops.sh get SONAR_ADMIN_PASSWORD)\n' SonarQube "$(sonar_url)"
  fi
}
