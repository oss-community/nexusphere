# shellcheck shell=bash
# The application's secrets in HashiCorp Vault: a KV (version 2) secret per
# environment at <mount>/<app>/<environment>, e.g. secret/hello-api/staging.
# The deploy step reads the secret of its environment and hands its keys to
# the application as environment variables (templates/scripts/app-secrets.sh):
# app.env on the machine (deploy/docker-host), a Kubernetes Secret
# (deploy/kubernetes), or a SealedSecret in the GitOps branch (gitops/argocd
# with secrets/sealed-secrets).
#
# The Vault is an existing one (VAULT_SERVER_URL and a token that may read the
# application's secrets) or one in Docker (compose.yml).  The one in Docker
# starts sealed after every restart; configure initialises it once, unseals it
# and gives the pipeline a token that may only read the application's secrets.

module_secrets() {
  ask_server VAULT "Vault" https://vault.example.com:8200
  if server_external VAULT; then
    ask_secret VAULT_TOKEN "Vault token that may read the application's secrets"
  else
    ask VAULT_HOST_PORT "Vault port on the Docker machine" 8200
  fi
  ask VAULT_KV_MOUNT "Mount path of the KV (version 2) secrets engine" secret
}

vault_url() { server_url VAULT "$(value VAULT_HOST_PORT 8200)"; }

jq_cmd() {
  command -v jq 2> /dev/null || sh "$DEVOPS_HOME/templates/scripts/tool.sh" jq || die "Could not download jq"
}

# vault_api <method> <path> [token] [JSON body]: prints the response body.
vault_api() {
  local args=(-s -X "$1" "$(vault_url)/v1/$2")
  [[ -n ${3:-} ]] && args+=(-H "X-Vault-Token: $3")
  [[ -n ${4:-} ]] && args+=(-H 'Content-Type: application/json' --data "$4")
  curl "${args[@]}"
}

# Read access to the application's secrets, for the pipeline.
vault_policy() {
  local mount app
  mount=$(value VAULT_KV_MOUNT secret) app=$(image_app_name)
  printf 'path "%s/data/%s/*" { capabilities = ["read"] }\n' "$mount" "$app"
}

module_configure() {
  local jq status root token mount app
  jq=$(jq_cmd)
  if server_external VAULT; then
    status=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Vault-Token: $(value VAULT_TOKEN)" "$(vault_url)/v1/auth/token/lookup-self")
    if [[ $status == 200 ]]; then log_ok "Vault $(vault_url) accepts the token"; else log_warn "Vault $(vault_url) answered HTTP $status to the token"; fi
    return 0
  fi
  wait_http "$(vault_url)/v1/sys/health" 120 '^(200|429|472|473|501|503)$' \
    || die "Vault did not start. Check '$DEVOPS_CMD logs vault'."

  if [[ $(vault_api GET sys/init | "$jq" -r .initialized) != true ]]; then
    status=$(vault_api PUT sys/init '' '{"secret_shares": 1, "secret_threshold": 1}')
    set_value VAULT_UNSEAL_KEY "$("$jq" -r '.keys_base64[0]' <<< "$status")" secret
    set_value VAULT_ROOT_TOKEN "$("$jq" -r .root_token <<< "$status")" secret
    set_value VAULT_TOKEN '' secret
    log_ok "Initialised Vault (unseal key and root token: $DEVOPS_CMD get VAULT_UNSEAL_KEY / VAULT_ROOT_TOKEN)"
  fi
  if [[ $(vault_api GET sys/seal-status | "$jq" -r .sealed) == true ]]; then
    [[ -n $(value VAULT_UNSEAL_KEY) ]] \
      || die "Vault is sealed and its unseal key is not in $DEVOPS_VALUES. Run '$DEVOPS_CMD destroy' to start over with an empty Vault."
    [[ $(vault_api PUT sys/unseal '' "{\"key\": \"$(value VAULT_UNSEAL_KEY)\"}" | "$jq" -r .sealed) == false ]] \
      || die "Could not unseal Vault with VAULT_UNSEAL_KEY"
    log_ok "Unsealed Vault"
  fi
  wait_http "$(vault_url)/v1/sys/health" 60 '^200$' || die "Vault is not ready. Check '$DEVOPS_CMD logs vault'."

  root=$(value VAULT_ROOT_TOKEN) mount=$(value VAULT_KV_MOUNT secret) app=$(image_app_name)
  if [[ $(vault_api GET sys/mounts "$root" | "$jq" --arg m "$mount/" '.data | has($m)') != true ]]; then
    vault_api POST "sys/mounts/$mount" "$root" '{"type": "kv", "options": {"version": "2"}}' > /dev/null
    log_ok "Enabled the KV secrets engine at $mount/"
  fi
  vault_api PUT "sys/policies/acl/$app-pipeline" "$root" \
    "$("$jq" -n --arg p "$(vault_policy)" '{policy: $p}')" > /dev/null
  token=$(value VAULT_TOKEN)
  if [[ -z $token ]] || [[ $(curl -s -o /dev/null -w '%{http_code}' -H "X-Vault-Token: $token" "$(vault_url)/v1/auth/token/lookup-self") != 200 ]]; then
    # A periodic token: the pipeline renews it on every run.
    token=$(vault_api POST auth/token/create-orphan "$root" \
      "{\"policies\": [\"$app-pipeline\"], \"period\": \"768h\", \"display_name\": \"mvn-devops-pipeline\"}" \
      | "$jq" -r .auth.client_token)
    [[ -n $token && $token != null ]] || die "Could not create the pipeline's Vault token"
    set_value VAULT_TOKEN "$token" secret
  fi
  log_ok "Vault runs; the pipeline reads $mount/$app/<environment>"
}

module_env() {
  pipeline_var VAULT_ADDR "$(server_pipeline_url VAULT vault 8200 "$(value VAULT_HOST_PORT 8200)")"
  pipeline_secret VAULT_TOKEN "$(value VAULT_TOKEN)"
  pipeline_var VAULT_KV_MOUNT "$(value VAULT_KV_MOUNT secret)"
  pipeline_var VAULT_APP "$(image_app_name)"
}

module_urls() {
  local mount app
  mount=$(value VAULT_KV_MOUNT secret) app=$(image_app_name)
  if server_external VAULT; then
    printf '  %-12s %s   (secrets: %s/%s/<environment>)\n' Vault "$(vault_url)" "$mount" "$app"
  else
    printf '  %-12s %s   (root token: devops.sh get VAULT_ROOT_TOKEN; secrets: %s/%s/<environment>)\n' \
      Vault "$(vault_url)/ui/" "$mount" "$app"
  fi
}
