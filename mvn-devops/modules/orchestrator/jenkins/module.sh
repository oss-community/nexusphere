# shellcheck shell=bash
# Jenkins orchestrator.
#
# render  writes generated/jenkins/Jenkinsfile and casc.yaml.  casc.yaml creates
#         the admin user, one secret-text credential per secret pipeline
#         variable and a pipeline job whose script is the Jenkinsfile.
# publish recreates the Jenkins container so it reloads env and casc.yaml.
#         With an existing server (JENKINS_SERVER_URL) it creates or updates
#         the credentials and the job through the REST API instead.
# run     triggers the job and streams its console.

job_name() { printf '%s' "$PROJECT_NAME"; }

# A shared server holds credentials of many projects, so ids get a prefix there.
credential_id() {
  if server_external JENKINS; then printf '%s-%s' "$PROJECT_NAME" "$1"; else printf '%s' "$1"; fi
}

module_secrets() {
  ask_server JENKINS Jenkins https://jenkins.example.com
  if server_external JENKINS; then
    log_dim "  Agents need git, ssh, Java 17 and Maven; the server needs the plugins workflow-aggregator,"
    log_dim "  git, credentials-binding, plain-credentials and timestamper."
    ask JENKINS_ADMIN_USER "Jenkins user that may create jobs and credentials" admin
    ask_secret JENKINS_API_TOKEN "API token of $(value JENKINS_ADMIN_USER) (user menu > Security > API Token)"
  else
    ask JENKINS_HOST_PORT "Jenkins port on the Docker machine" 8080
    ask JENKINS_ADMIN_USER "Jenkins admin user" admin
    ask_secret JENKINS_ADMIN_PASSWORD "Jenkins admin password" "$(random_password)"
  fi
  ask JENKINS_TRIGGER "Start a build on every push: poll, webhook or none" poll
  if [[ $(value JENKINS_TRIGGER) == webhook ]]; then
    log_dim "  GitHub must reach Jenkins; for Jenkins on your machine use an ngrok URL (docs/ngrok.md)."
    ask JENKINS_PUBLIC_URL "Public URL of Jenkins for the GitHub webhook" "$(value JENKINS_SERVER_URL)"
  fi
}

# GitHub calls <public url>/github-webhook/ on every push (github plugin).
webhook_url() { printf '%s/github-webhook/' "$(value JENKINS_PUBLIC_URL | sed 's:/*$::')"; }

# Id of the repository webhook pointing to this Jenkins, if any.
webhook_id() {
  github_api GET "/repos/$(value GITHUB_REPOSITORY)/hooks" \
    | jq -r --arg u "$(webhook_url)" '.[]? | select(.config.url == $u) | .id' | head -n 1
}

register_webhook() {
  local body status
  [[ $(value JENKINS_TRIGGER poll) == webhook ]] || return 0
  [[ -n $(value JENKINS_PUBLIC_URL) ]] || { log_warn "JENKINS_PUBLIC_URL is empty; no webhook registered."; return 0; }
  if [[ -n $(webhook_id) ]]; then
    log_dim "  webhook $(webhook_url) already registered"
    return 0
  fi
  body=$(jq -nc --arg u "$(webhook_url)" '{name: "web", active: true, events: ["push"], config: {url: $u, content_type: "json"}}')
  status=$(github_api POST "/repos/$(value GITHUB_REPOSITORY)/hooks" --data "$body" -o /dev/null -w '%{http_code}')
  if [[ $status == 201 ]]; then
    log_ok "Registered the GitHub webhook $(webhook_url)"
  else
    log_warn "Could not register the webhook (HTTP $status). The token needs admin:repo_hook (docs/github-setup.md)."
  fi
}

# destroy: remove the webhook this project registered.
module_destroy() {
  local id
  [[ $(value JENKINS_TRIGGER poll) == webhook && -n $(value JENKINS_PUBLIC_URL) ]] || return 0
  id=$(webhook_id)
  [[ -n $id ]] || return 0
  if [[ $(github_api DELETE "/repos/$(value GITHUB_REPOSITORY)/hooks/$id" -o /dev/null -w '%{http_code}') == 204 ]]; then
    log_ok "Removed the GitHub webhook $(webhook_url)"
  else
    log_warn "Could not remove the GitHub webhook $(webhook_url); delete it under Settings > Webhooks."
  fi
}

groovy_escape() { local v=${1//\\/\\\\}; printf '%s' "${v//\'/\\\'}"; }
xml_escape() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g'; }

render_jenkinsfile() {
  local name args flags key line
  flags=$(maven_flags "" "$CI_SETTINGS")
  printf 'pipeline {\n  agent any\n'
  printf '  options {\n    timestamps()\n    disableConcurrentBuilds()\n  }\n'
  # Triggers take effect after the first build, which records the repository.
  case $(value JENKINS_TRIGGER poll) in
    poll) printf "  triggers {\n    pollSCM('H/2 * * * *')\n  }\n" ;;
    webhook) printf '  triggers {\n    githubPush()\n  }\n' ;;
  esac
  printf '  environment {\n'
  while IFS= read -r key; do
    printf "    %s = credentials('%s')\n" "$key" "$(credential_id "$key")"
  done < <(pipeline_secret_keys)
  # Jenkins in Docker gets the plain variables from env/pipeline.env.
  if server_external JENKINS; then
    while IFS= read -r line; do
      key=${line%%=*}
      is_secret "$key" && continue
      printf "    %s = '%s'\n" "$key" "$(groovy_escape "${line#*=}")"
    done < "$DEVOPS_ENV/pipeline.env"
  fi
  printf '  }\n  stages {\n'
  printf "    stage('checkout') {\n      steps {\n"
  printf "        git url: env.GITHUB_URL + '/' + env.GITHUB_REPOSITORY + '.git', branch: env.GIT_BRANCH, credentialsId: '%s'\n" "$(credential_id github-https)"
  printf "        sh '%s'\n      }\n    }\n" "$(pipeline_ci_setup)"
  while IFS='|' read -r _ _ name args; do
    printf "    stage('%s') {\n      steps {\n        sh '%s'\n      }\n    }\n" "$name" "$(stage_command "$flags" "$args")"
  done < <(pipeline_stages)
  printf '  }\n}\n'
}

render_casc() {
  local jenkinsfile=$1 key
  cat <<EOF
# Generated by mvn-devops. Re-create with: devops.sh render
jenkins:
  systemMessage: "mvn-devops pipeline for $PROJECT_NAME"
  numExecutors: 2
  securityRealm:
    local:
      allowsSignup: false
      users:
        - id: "\${JENKINS_ADMIN_USER}"
          password: "\${JENKINS_ADMIN_PASSWORD}"
  authorizationStrategy:
    loggedInUsersCanDoAnything:
      allowAnonymousRead: false
unclassified:
  location:
    url: "$(host_url "$(value JENKINS_HOST_PORT 8080)")/"
credentials:
  system:
    domainCredentials:
      - credentials:
          - usernamePassword:
              scope: GLOBAL
              id: "github-https"
              description: "GitHub user and token"
              username: "\${GITHUB_USERNAME}"
              password: "\${GITHUB_TOKEN}"
EOF
  while IFS= read -r key; do
    cat <<EOF
          - string:
              scope: GLOBAL
              id: "$key"
              secret: "\${$key:-}"
EOF
  done < <(pipeline_secret_keys)
  cat <<EOF
jobs:
  - script: |
      pipelineJob('$(job_name)') {
        description('Generated by mvn-devops')
        definition {
          cps {
            sandbox(true)
            script('''
EOF
  # casc substitutes \${VAR}; ^\${ keeps a literal \${ in the job script.
  sed -e 's/\${/^${/g' -e 's/^/              /' "$jenkinsfile"
  cat <<EOF
            ''')
          }
        }
      }
EOF
}

module_render() {
  local dir="$DEVOPS_GENERATED/jenkins"
  mkdir -p "$dir"
  render_jenkinsfile > "$DEVOPS_GENERATED/Jenkinsfile"
  render_casc "$DEVOPS_GENERATED/Jenkinsfile" > "$dir/casc.yaml"
  log_ok "Wrote $DEVOPS_GENERATED/Jenkinsfile"
  log_ok "Wrote $dir/casc.yaml"
}

# up: casc.yaml must exist before the container starts.
module_prepare() {
  module_render
}

jenkins_url() { server_url JENKINS "$(value JENKINS_HOST_PORT 8080)"; }

# jenkins_api <method> <path> [curl args]: authenticated call with a crumb.
jenkins_api() {
  local method=$1 path=$2; shift 2
  local auth jar crumb
  if server_external JENKINS; then
    auth="$(value JENKINS_ADMIN_USER admin):$(value JENKINS_API_TOKEN)"
  else
    auth="$(value JENKINS_ADMIN_USER admin):$(value JENKINS_ADMIN_PASSWORD)"
  fi
  jar=$(mktemp)
  crumb=$(curl -s -c "$jar" -u "$auth" "$(jenkins_url)/crumbIssuer/api/json" | jq -r '.crumbRequestField + ":" + .crumb' 2>/dev/null || true)
  curl -s -b "$jar" -u "$auth" -H "$crumb" -X "$method" "$(jenkins_url)$path" "$@"
  rm -f "$jar"
}

module_configure() {
  local status
  if server_external JENKINS; then
    wait_http "$(jenkins_url)/login" 60 || die "Jenkins at $(jenkins_url) does not answer."
    status=$(jenkins_api GET /api/json -o /dev/null -w '%{http_code}')
    [[ $status == 200 ]] || die "$(jenkins_url) rejects $(value JENKINS_ADMIN_USER admin) with JENKINS_API_TOKEN (HTTP $status)."
    log_ok "Logged in to $(jenkins_url) as $(value JENKINS_ADMIN_USER admin)"
    return
  fi
  wait_http "$(jenkins_url)/login" 600 '^200$' || die "Jenkins did not start. Check '$DEVOPS_CMD logs jenkins'."
}

# ---------------------------------------------------------------- existing server

credential_xml() {
  local kind=$1 id=$2 user=$3 secret=$4
  if [[ $kind == string ]]; then
    printf '<org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>\n'
    printf '  <scope>GLOBAL</scope><id>%s</id><description>mvn-devops %s</description>\n' "$id" "$PROJECT_NAME"
    printf '  <secret>%s</secret>\n' "$(printf '%s' "$secret" | xml_escape)"
    printf '</org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl>\n'
  else
    printf '<com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>\n'
    printf '  <scope>GLOBAL</scope><id>%s</id><description>mvn-devops %s</description>\n' "$id" "$PROJECT_NAME"
    printf '  <username>%s</username>\n' "$(printf '%s' "$user" | xml_escape)"
    printf '  <password>%s</password>\n' "$(printf '%s' "$secret" | xml_escape)"
    printf '</com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl>\n'
  fi
}

# put_credential <string|userpass> <id> <user> <secret>: update, or create.
put_credential() {
  local store=/credentials/store/system/domain/_ status
  status=$(credential_xml "$@" | jenkins_api POST "$store/credential/$2/config.xml" \
    -H 'Content-Type: application/xml' --data-binary @- -o /dev/null -w '%{http_code}')
  if [[ $status == 404 ]]; then
    status=$(credential_xml "$@" | jenkins_api POST "$store/createCredentials" \
      -H 'Content-Type: application/xml' --data-binary @- -o /dev/null -w '%{http_code}')
  fi
  [[ $status == 200 ]] || die "Could not store credential $2 on $(jenkins_url) (HTTP $status)."
}

job_xml() {
  printf "<?xml version='1.1' encoding='UTF-8'?>\n<flow-definition>\n"
  printf '  <description>Generated by mvn-devops</description>\n'
  printf '  <keepDependencies>false</keepDependencies>\n  <properties/>\n'
  printf '  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition">\n'
  printf '    <script>%s</script>\n' "$(xml_escape < "$DEVOPS_GENERATED/Jenkinsfile")"
  printf '    <sandbox>true</sandbox>\n  </definition>\n  <disabled>false</disabled>\n</flow-definition>\n'
}

publish_existing() {
  local key line status
  log_step "Jenkins: credentials"
  put_credential userpass "$(credential_id github-https)" "$(value GITHUB_USERNAME)" "$(value GITHUB_TOKEN)"
  while IFS= read -r line; do
    key=${line%%=*}
    is_secret "$key" || continue
    put_credential string "$(credential_id "$key")" "" "${line#*=}"
  done < "$DEVOPS_ENV/pipeline.env"
  log_ok "Stored the credentials with the prefix $PROJECT_NAME-"

  log_step "Jenkins: job"
  status=$(job_xml | jenkins_api POST "/job/$(job_name)/config.xml" \
    -H 'Content-Type: application/xml' --data-binary @- -o /dev/null -w '%{http_code}')
  if [[ $status == 404 ]]; then
    status=$(job_xml | jenkins_api POST "/createItem?name=$(job_name)" \
      -H 'Content-Type: application/xml' --data-binary @- -o /dev/null -w '%{http_code}')
  fi
  [[ $status == 200 ]] || die "Could not create the job $(job_name) on $(jenkins_url) (HTTP $status)."
}

module_publish() {
  register_webhook
  if server_external JENKINS; then
    publish_existing
    log_ok "Job '$(job_name)' is ready at $(jenkins_url)/job/$(job_name)/"
    return
  fi
  log_step "Jenkins: reload configuration"
  compose up -d --no-deps --force-recreate jenkins
  module_configure
  local status
  status=$(jenkins_api GET "/job/$(job_name)/api/json" -o /dev/null -w '%{http_code}')
  [[ $status == 200 ]] || die "Job '$(job_name)' was not created (HTTP $status). Check '$DEVOPS_CMD logs jenkins'."
  log_ok "Job '$(job_name)' is ready at $(jenkins_url)/job/$(job_name)/"
}

module_run() {
  local headers location number='' start=0 size more result
  headers=$(mktemp)
  jenkins_api POST "/job/$(job_name)/build" -D "$headers" -o /dev/null
  location=$(awk 'tolower($1) == "location:" { print $2 }' "$headers" | tr -d '\r')
  rm -f "$headers"
  [[ -n $location ]] || die "Jenkins did not accept the build request. Is it published? Run '$DEVOPS_CMD publish'."

  log_step "Jenkins: waiting for an executor"
  while [[ -z $number || $number == null ]]; do
    sleep 2
    number=$(jenkins_api GET "${location#"$(jenkins_url)"}api/json" | jq -r '.executable.number // empty')
  done
  log_info "Build #$number: $(jenkins_url)/job/$(job_name)/$number/console"

  headers=$(mktemp)
  while true; do
    jenkins_api GET "/job/$(job_name)/$number/logText/progressiveText?start=$start" -D "$headers"
    size=$(awk 'tolower($1) == "x-text-size:" { print $2 }' "$headers" | tr -d '\r')
    more=$(awk 'tolower($1) == "x-more-data:" { print $2 }' "$headers" | tr -d '\r')
    start=${size:-$start}
    [[ $more == true ]] || break
    sleep 2
  done
  rm -f "$headers"

  result=$(jenkins_api GET "/job/$(job_name)/$number/api/json" | jq -r '.result')
  [[ $result == SUCCESS ]] || die "Build #$number finished with $result"
  log_ok "Build #$number succeeded"
}

module_urls() {
  if server_external JENKINS; then
    printf '  %-12s %s   (%s)\n' Jenkins "$(jenkins_url)" "$(value JENKINS_ADMIN_USER admin)"
  else
    printf '  %-12s %s   (%s / devops.sh get JENKINS_ADMIN_PASSWORD)\n' Jenkins "$(jenkins_url)" "$(value JENKINS_ADMIN_USER admin)"
  fi
}
