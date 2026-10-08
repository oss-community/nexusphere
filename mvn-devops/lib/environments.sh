# shellcheck shell=bash
# Deployment environments.
#
# ENVIRONMENTS lists the environments in the order an image goes through
# them, e.g. "dev test staging prod".  Projects that do not set it have
# "staging production".  The image is built once (the cd phase) and the same
# image is deployed to each environment in turn.
#
# An environment with ENV_<NAME>_APPROVAL=yes waits until someone approves it
# (by default only the last one).  The environments after it, up to the next
# one that needs approval, follow it without asking.  So the pipeline falls
# into groups, each started by an approval:
#
#   staging production            cd + staging | production
#   dev test staging prod         cd + dev + test + staging | prod
#
# Modules add per-environment stages with the environment's name as phase
# (see lib/pipeline.sh) and keep per-environment values as <PREFIX>_<NAME>_*,
# e.g. DEPLOY_STAGING_PORT.

DEFAULT_ENVIRONMENTS='staging production'

# The environments, in order.
environments() {
  local list
  list=$(value ENVIRONMENTS)
  [[ -n $list ]] || list=$(conf_get ENVIRONMENTS 2> /dev/null || true)
  list=$(printf '%s' "${list:-$DEFAULT_ENVIRONMENTS}" | tr ',' ' ' | xargs)
  printf '%s' "${list:-$DEFAULT_ENVIRONMENTS}"
}

upper() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]'; }

env_last() { local list; list=$(environments); printf '%s' "${list##* }"; }

is_environment() { [[ " $(environments) " == *" $1 "* ]]; }

# env_resolve <name>: the environment a name stands for.  "prod" is the last
# environment when no environment has that name, so "run --phase prod" and
# "rollback prod" work whatever the last one is called.
env_resolve() {
  if is_environment "$1"; then
    printf '%s' "$1"
  elif [[ $1 == prod || $1 == production ]]; then
    env_last
  else
    return 1
  fi
}

# env_offset <environment>: 0 for the last environment, 1 for the one before
# it, ...  Default ports count up from the last environment, so that
# "staging production" keeps the ports it had before environments could be
# chosen (production 8180, staging 8181).
env_offset() {
  local -a list
  local i
  read -r -a list <<< "$(environments)"
  for i in "${!list[@]}"; do
    [[ ${list[$i]} == "$1" ]] && { printf '%s' $(( ${#list[@]} - 1 - i )); return; }
  done
  printf '0'
}

# env_setting <environment> <SETTING> [default]: ENV_<NAME>_<SETTING>.
env_setting() {
  local key val
  key="ENV_$(upper "$1")_$2"
  val=$(value "$key")
  [[ -n $val ]] || val=$(conf_get "$key" 2> /dev/null || true)
  printf '%s' "${val:-${3:-}}"
}

env_approval() {
  local default=no
  [[ $1 == "$(env_last)" ]] && default=yes
  [[ $(env_setting "$1" APPROVAL "$default") == yes ]]
}

# The environments that wait for approval, in order.
env_gates() {
  local env
  for env in $(environments); do
    env_approval "$env" && printf '%s\n' "$env"
  done
  return 0
}

# env_group <environment>: the approval an environment's stages wait for:
# its own when it needs approval, else the one of the environment before it,
# "cd" for the environments before the first approval.
env_group() {
  local env group=cd
  for env in $(environments); do
    env_approval "$env" && group=$env
    [[ $env == "$1" ]] && { printf '%s' "$group"; return; }
  done
  printf 'cd'
}

# Checks ENVIRONMENTS and asks whether each environment needs approval.
# Called by "secrets" when a module deploys the application.
environments_secrets() {
  local env list seen=' ' default
  ask ENVIRONMENTS "Deployment environments, in the order the image goes through them" "$DEFAULT_ENVIRONMENTS"
  list=$(environments)
  for env in $list; do
    [[ $env =~ ^[a-z][a-z0-9]*$ ]] \
      || die "Environment '$env': use lowercase letters and digits, starting with a letter (ENVIRONMENTS in $DEVOPS_CONF)"
    [[ $env == ci || $env == cd ]] && die "Environment '$env': ci and cd are the names of pipeline phases"
    [[ $seen == *" $env "* ]] && die "Environment '$env' is listed twice in ENVIRONMENTS"
    seen+="$env "
  done
  set_value ENVIRONMENTS "$list"
  for env in $list; do
    default=no
    [[ $env == "$(env_last)" ]] && default=yes
    ask "ENV_$(upper "$env")_APPROVAL" "Approval before deploying to $env (yes or no)" "$default"
  done
}

# True when a selected module deploys the application.
deploys_application() { [[ " $MODULES " == *" deploy/"* || " $MODULES " == *" gitops/"* ]]; }

# rollback_args [environment] [--to TAG]: sets ROLLBACK_ENV (the last
# environment by default) and ROLLBACK_TAG for a module_rollback hook.
# shellcheck disable=SC2034  # read by the module_rollback hooks
rollback_args() {
  ROLLBACK_ENV=$(env_last) ROLLBACK_TAG=''
  while (( $# )); do
    case $1 in
      --to) ROLLBACK_TAG=${2:?--to needs a tag}; shift 2 ;;
      -*) die "rollback: unknown option $1 (use [environment] [--to TAG])" ;;
      *)
        ROLLBACK_ENV=$(env_resolve "$1") || die "rollback: unknown environment '$1' (one of: $(environments))"
        shift ;;
    esac
  done
}
