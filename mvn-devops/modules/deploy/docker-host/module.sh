# shellcheck shell=bash
# Deploys the image of the commit with Docker Compose over SSH to each
# environment in turn (lib/environments.sh); an environment that needs
# approval waits for it.  Each deployment is checked at its health endpoint;
# when the check fails the previous image is put back.  "devops.sh rollback"
# puts it back by hand.
#
# The machines are given as ssh:// URLs, e.g. ssh://deploy@app.example.com:22:
# DEPLOY_SERVER_URL for the first environment and DEPLOY_<NAME>_SERVER_URL
# for each other one (the same machine by default).  They need Docker with
# the compose plugin and curl or wget.  Without a URL every environment goes
# to a simulated machine in Docker (compose.yml), on the port
# DEPLOY_<NAME>_PORT of this machine.

module_secrets() {
  local env first
  require_image_module
  first=${ENVIRONMENTS%% *}
  ask_server DEPLOY "Machine of $first" "ssh://deploy@$first.example.com"
  if server_external DEPLOY; then
    for env in $ENVIRONMENTS; do
      [[ $env == "$first" ]] && continue
      ask "DEPLOY_$(upper "$env")_SERVER_URL" "Machine of $env: URL such as ssh://deploy@app.example.com" "$(value DEPLOY_SERVER_URL)"
    done
    ask_local DEPLOY_SSH_KEY_FILE "Private SSH key that may log in to the machines (empty: generate one)" ""
  else
    ask DEPLOY_SSH_HOST_PORT "SSH port of the simulated machine on the Docker machine" 2222
  fi
  for env in $ENVIRONMENTS; do
    ask "DEPLOY_$(upper "$env")_PORT" "Port of the application on the machine of $env" $((8180 + $(env_offset "$env")))
  done
  ask DEPLOY_HEALTH_PATH "Health check path of the application" /actuator/health
}

deploy_key() {
  local file
  file=$(value DEPLOY_SSH_KEY_FILE)
  printf '%s' "${file:-$DEVOPS_KEYS/deploy}"
}

ensure_key() {
  local key
  key=$(deploy_key)
  if [[ ! -f $key ]]; then
    [[ -z $(value DEPLOY_SSH_KEY_FILE) ]] || die "DEPLOY_SSH_KEY_FILE $key does not exist"
    mkdir -p "$(dirname "$key")"
    ssh-keygen -q -t ed25519 -N '' -C "mvn-devops $PROJECT_NAME deploy" -f "$key"
    log_ok "Generated the deploy key $key"
  fi
  [[ -f $key.pub ]] || ssh-keygen -y -f "$key" > "$key.pub"
  set_value DEPLOY_SSH_PUBLIC_KEY "$(cat "$key.pub")"
}

# The simulated machine gets the public key when it starts.
module_prepare() {
  ensure_key
}

# server_of <environment>: ssh:// URL of the machine, empty for the simulated one.
server_of() { deploy_server_of "$1"; }

# ssh_target <environment> <host|pipeline>: "user@host port" as this machine
# or the pipeline reaches the machine.
ssh_target() {
  local url port=22
  url=$(server_of "$1")
  if [[ -n $url ]]; then
    url=${url#ssh://}; url=${url%%/*}
    if [[ $url =~ :([0-9]+)$ ]]; then port=${BASH_REMATCH[1]}; url=${url%:*}; fi
    printf '%s %s' "$url" "$port"
  elif [[ $2 == pipeline && ${DEVOPS_RUNS_IN:-host} == docker ]]; then
    printf 'root@deploy-host 22'
  else
    printf 'root@%s %s' "$(devops_host)" "$(value DEPLOY_SSH_HOST_PORT 2222)"
  fi
}

# Address of the application in the environment, for people.
app_url() {
  local target host
  target=$(ssh_target "$1" host)
  host=${target%% *}; host=${host#*@}
  printf 'http://%s:%s' "$host" "$(value "DEPLOY_$(upper "$1")_PORT")"
}

# deploy_ssh <environment> <known hosts file> <port> <user@host> <command>
deploy_ssh() {
  ssh -i "$(deploy_key)" -o BatchMode=yes -o ConnectTimeout=15 -o UserKnownHostsFile="$2" \
    -o StrictHostKeyChecking=yes -o HostKeyAlias="mvn-devops-$1" -p "$3" "$4" "$5"
}

# configure: record the host keys of the machines, so the pipeline checks
# them, and check that the key may log in and Docker compose is there.
module_configure() {
  local env target dest port known scanned tries
  ensure_key
  known=$(mktemp)
  for env in $ENVIRONMENTS; do
    target=$(ssh_target "$env" host)
    dest=${target%% *}; port=${target##* }
    scanned=''
    for (( tries = 0; tries < 30; tries++ )); do
      scanned=$(ssh-keyscan -p "$port" "${dest#*@}" 2> /dev/null || true)
      [[ -n $scanned ]] && break
      server_external DEPLOY && break
      sleep 2
    done
    if [[ -z $scanned ]]; then
      server_external DEPLOY || die "The simulated machine does not answer on port $port. Check '$DEVOPS_CMD logs deploy-host'."
      log_warn "$env: no SSH server answers at ${dest#*@}:$port"
      continue
    fi
    awk -v alias="mvn-devops-$env" '{ $1 = alias; print }' <<< "$scanned" >> "$known"
    if deploy_ssh "$env" "$known" "$port" "$dest" 'docker compose version' > /dev/null 2>&1; then
      log_ok "$env: $dest can run Docker compose"
    else
      log_warn "$env: cannot run 'docker compose' as $dest on port $port. Add this key to ~/.ssh/authorized_keys there:"
      log_dim "  $(value DEPLOY_SSH_PUBLIC_KEY)"
    fi
  done
  set_value DEPLOY_KNOWN_HOSTS_B64 "$(base64 < "$known" | tr -d '\n')"
  rm -f "$known"
}

module_env() {
  local env target key
  pipeline_var DEPLOY_NAME "$(image_app_name)"
  for env in $ENVIRONMENTS; do
    target=$(ssh_target "$env" pipeline)
    pipeline_var "DEPLOY_$(upper "$env")_TARGET" "${target%% *}"
    pipeline_var "DEPLOY_$(upper "$env")_SSH_PORT" "${target##* }"
    pipeline_var "DEPLOY_$(upper "$env")_PORT" "$(value "DEPLOY_$(upper "$env")_PORT")"
  done
  pipeline_var DEPLOY_CONTAINER_PORT "$(value IMAGE_PORT 8080)"
  pipeline_var DEPLOY_HEALTH_PATH "$(value DEPLOY_HEALTH_PATH /actuator/health)"
  # The simulated machine reaches the published ports through the host gateway.
  if server_external DEPLOY; then
    pipeline_var DEPLOY_CHECK_HOST localhost
  else
    pipeline_var DEPLOY_CHECK_HOST host.docker.internal
  fi
  pipeline_var DEPLOY_KNOWN_HOSTS_B64 "$(value DEPLOY_KNOWN_HOSTS_B64)"
  key=$(deploy_key)
  if [[ -f $key ]]; then
    pipeline_secret DEPLOY_SSH_KEY_B64 "$(base64 < "$key" | tr -d '\n')"
  else
    pipeline_secret DEPLOY_SSH_KEY_B64 ''
  fi
}

module_stages() {
  local env
  for env in $ENVIRONMENTS; do
    shell_stage 80 "$env" "deploy-$env" "sh \"\$DEVOPS_SCRIPTS/deploy-compose.sh\" $env"
  done
}

# rollback [environment] [--to TAG]
module_rollback() {
  local env tag target
  rollback_args "$@"
  env=$ROLLBACK_ENV tag=$ROLLBACK_TAG
  target=$(ssh_target "$env" host)
  log_step "Rollback of $env${tag:+ to $tag}"
  (
    source_pipeline_env
    export "DEPLOY_$(upper "$env")_TARGET=${target%% *}" "DEPLOY_$(upper "$env")_SSH_PORT=${target##* }"
    export DEPLOY_SSH_KEY_B64
    DEPLOY_SSH_KEY_B64=$(base64 < "$(deploy_key)" | tr -d '\n')
    sh "$DEVOPS_HOME/templates/scripts/deploy-compose.sh" "$env" rollback "$tag"
  ) || die "Rollback of $env failed"
  log_ok "$env is rolled back: $(app_url "$env")"
}

# destroy: the simulated machine's applications run on this Docker daemon,
# outside the compose project; remove them and their data before the
# machine goes.
module_destroy() {
  server_external DEPLOY && return 0
  compose exec -T deploy-host sh -c \
    'for d in "$HOME"/mvn-devops/*/; do [ -f "$d/compose.yml" ] && (cd "$d" && docker compose down --volumes); done; true' \
    > /dev/null 2>&1 || true
}

module_urls() {
  local env
  for env in $ENVIRONMENTS; do
    printf '  %-12s %s   (rollback: devops.sh rollback %s)\n' "${env^}" "$(app_url "$env")" "$env"
  done
}
