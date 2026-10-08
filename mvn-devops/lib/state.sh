# shellcheck shell=bash
# Project state: profile, value store and generated env files.
#
# "$PROJECT_DIR/devops.conf" is committed with the project: the selected
# modules and every answer that is neither a secret nor specific to one
# person or machine, so everyone who clones the project gets the same setup.
#
# Everything else lives in "$PROJECT_DIR/.devops" (git-ignored):
#
#   values/<KEY>        one file per value (chmod 600); .secret lists masked keys
#   env/compose.env     every value, used by docker compose for ${VAR} substitution
#   env/pipeline.env    variables handed to the pipeline (KEY=VALUE)
#   env/pipeline.sh     the same variables as bash exports
#   env/pipeline.keys   names of the pipeline variables, one per line
#   generated/          rendered pipeline definitions (Jenkins, Concourse, ...)
#   keys/               generated SSH keys

state_init_paths() {
  DEVOPS_STATE="$PROJECT_DIR/.devops"
  DEVOPS_VALUES="$DEVOPS_STATE/values"
  DEVOPS_ENV="$DEVOPS_STATE/env"
  DEVOPS_GENERATED="$DEVOPS_STATE/generated"
  DEVOPS_KEYS="$DEVOPS_STATE/keys"
  DEVOPS_CONF="$PROJECT_DIR/devops.conf"
  # Selection file of early copies of mvn-devops, moved to devops.conf on first use.
  DEVOPS_PROFILE="$DEVOPS_STATE/profile.conf"
  export DEVOPS_STATE DEVOPS_VALUES DEVOPS_ENV DEVOPS_GENERATED DEVOPS_KEYS DEVOPS_CONF DEVOPS_PROFILE
}

state_ensure_dirs() {
  mkdir -p "$DEVOPS_VALUES" "$DEVOPS_ENV" "$DEVOPS_GENERATED" "$DEVOPS_KEYS"
  chmod 700 "$DEVOPS_VALUES" "$DEVOPS_KEYS" 2>/dev/null || true
  [[ -f "$DEVOPS_STATE/.gitignore" ]] || printf '*\n' > "$DEVOPS_STATE/.gitignore"
}

# ---------------------------------------------------------------- devops.conf

# KEY=value lines; the value is the rest of the line, never evaluated.
conf_get() {
  local line found=1 val=''
  [[ -f $DEVOPS_CONF ]] || return 1
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%"$CR"}
    if [[ $line == "$1="* ]]; then val=${line#*=}; found=0; fi
  done < "$DEVOPS_CONF"
  (( found == 0 )) && printf '%s' "$val"
  return $found
}

conf_set() {
  local key=$1 val=$2 line replaced=0 tmp current
  if current=$(conf_get "$key") && [[ $current == "$val" ]]; then return 0; fi
  tmp=$(mktemp)
  if [[ -f $DEVOPS_CONF ]]; then
    while IFS= read -r line || [[ -n $line ]]; do
      line=${line%"$CR"}
      if [[ $line == "$key="* ]]; then
        (( replaced )) || printf '%s=%s\n' "$key" "$val"
        replaced=1
      else
        printf '%s\n' "$line"
      fi
    done < "$DEVOPS_CONF" > "$tmp"
  else
    printf '# mvn-devops settings of this project. Commit this file: everyone who\n# clones the project gets the same tools and answers. Secrets are not here.\n' > "$tmp"
  fi
  (( replaced )) || printf '%s=%s\n' "$key" "$val" >> "$tmp"
  cat "$tmp" > "$DEVOPS_CONF"
  rm -f "$tmp"
}

# ---------------------------------------------------------------- profile

# Reads PROJECT_NAME, ORCHESTRATOR and MODULES; fails when nothing was selected.
profile_read() {
  if conf_get MODULES > /dev/null; then
    PROJECT_NAME=$(conf_get PROJECT_NAME || true)
    ORCHESTRATOR=$(conf_get ORCHESTRATOR || true)
    MODULES=$(conf_get MODULES)
  elif [[ -f $DEVOPS_PROFILE ]]; then
    # shellcheck disable=SC1090
    source "$DEVOPS_PROFILE"
    profile_save
    log_info "Moved the tool selection to $DEVOPS_CONF; commit it with the project."
  else
    return 1
  fi
}

profile_exists() { conf_get MODULES > /dev/null || [[ -f $DEVOPS_PROFILE ]]; }

profile_load() {
  profile_read || die "No tools selected for this project yet. Run '$DEVOPS_CMD init' first."
  : "${PROJECT_NAME:?devops.conf is missing PROJECT_NAME}"
  : "${ORCHESTRATOR:?devops.conf is missing ORCHESTRATOR}"
  : "${MODULES:?devops.conf is missing MODULES}"
  export PROJECT_NAME ORCHESTRATOR MODULES
}

profile_save() {
  conf_set PROJECT_NAME "$PROJECT_NAME"
  conf_set ORCHESTRATOR "$ORCHESTRATOR"
  conf_set MODULES "$MODULES"
  rm -f "$DEVOPS_PROFILE"
}

# ---------------------------------------------------------------- values

has_value() { [[ -f "$DEVOPS_VALUES/$1" ]]; }

value() {
  local key=$1
  if [[ -f "$DEVOPS_VALUES/$key" ]]; then
    cat "$DEVOPS_VALUES/$key"
  else
    printf '%s' "${2:-}"
  fi
}

require_value() {
  has_value "$1" || die "Value '$1' is not set. Run '$DEVOPS_CMD secrets' first."
  value "$1"
}

is_secret() { grep -qx "$1" "$DEVOPS_VALUES/.secret" 2>/dev/null; }

set_value() {
  local key=$1 val=$2 secret=${3:-}
  [[ $key =~ ^[A-Z][A-Z0-9_]*$ ]] || die "Invalid value name '$key'"
  [[ $val != *$'\n'* ]] || die "Value '$key' must be a single line"
  state_ensure_dirs
  ( umask 077; printf '%s' "$val" > "$DEVOPS_VALUES/$key" )
  if [[ $secret == secret ]] && ! is_secret "$key"; then
    printf '%s\n' "$key" >> "$DEVOPS_VALUES/.secret"
  fi
}

mask() {
  local key=$1 val=$2
  if is_secret "$key" && [[ -n $val ]]; then
    printf '%s' '********'
  else
    printf '%s' "$val"
  fi
}

# ask KEY "Question" [default]
# The answer is stored in .devops/values and shared through devops.conf.
# devops.conf wins over the local value unless DEVOPS_RECONFIGURE=1, so the
# whole team uses the committed answers; an existing local value is kept.
# With a ready-made pipeline (DEVOPS_PRESET=1, see pipelines/) the default is
# taken without asking, except for personal answers and secrets that have
# none, such as the GitHub user and token.
ask() {
  local key=$1 question=$2 default=${3:-} current answer shared=1 team
  [[ ${DEVOPS_ASK_LOCAL:-0} == 1 ]] && shared=0
  is_secret "$key" && shared=0
  if (( shared )) && team=$(conf_get "$key"); then
    if [[ ${DEVOPS_RECONFIGURE:-0} != 1 ]]; then
      set_value "$key" "$team"
      log_dim "  $key = $team"
      return
    fi
    default=$team
  elif has_value "$key" && [[ ${DEVOPS_RECONFIGURE:-0} != 1 ]]; then
    (( shared )) && conf_set "$key" "$(value "$key")"
    log_dim "  $key = $(mask "$key" "$(value "$key")")"
    return
  fi
  current=$(value "$key" "$default")
  if [[ ${DEVOPS_DEFAULTS:-0} == 1 ]] || { [[ ${DEVOPS_PRESET:-0} == 1 ]] && { (( shared )) || [[ -n $current ]]; }; }; then
    answer=$current
    [[ ${DEVOPS_PRESET:-0} == 1 ]] && log_dim "  $key = $answer"
  else
    read -r -p "  $question [$current]: " answer || true
    answer=${answer:-$current}
  fi
  set_value "$key" "$answer"
  (( shared )) && conf_set "$key" "$answer"
  return 0
}

# ask_local KEY "Question" [default]
# Like ask, for answers that belong to one person or machine (user names,
# the Docker address): never written to devops.conf.
ask_local() { DEVOPS_ASK_LOCAL=1 ask "$@"; }

# ask_secret KEY "Question" [default]
# Input is hidden; an empty answer keeps the current value or the default.
ask_secret() {
  local key=$1 question=$2 default=${3:-} current answer shown
  if has_value "$key" && [[ ${DEVOPS_RECONFIGURE:-0} != 1 ]]; then
    set_value "$key" "$(value "$key")" secret
    log_dim "  $key = ********"
    return
  fi
  current=$(value "$key" "$default")
  if [[ ${DEVOPS_DEFAULTS:-0} == 1 ]] || [[ ${DEVOPS_PRESET:-0} == 1 && -n $current ]]; then
    answer=$current
  else
    shown='empty'
    [[ -n $current ]] && shown='keep current'
    read -r -s -p "  $question [$shown]: " answer || true
    printf '\n'
    answer=${answer:-$current}
  fi
  set_value "$key" "$answer" secret
}

# A random password that satisfies the usual complexity rules.  Reads a fixed
# number of bytes: a tr reading /dev/urandom never ends where SIGPIPE is
# ignored (it is on macOS GitHub runners).
random_password() {
  local raw=''
  while (( ${#raw} < 16 )); do
    raw+=$(head -c 256 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')
  done
  printf 'Dv-%sa1' "${raw:0:16}"
}

# ---------------------------------------------------------------- env files

# Called from a module's module_env hook.
pipeline_var() {
  local key=$1 val=${2-}
  [[ -n ${PIPELINE_VARS_FILE:-} ]] || die "pipeline_var used outside module_env"
  [[ $val != *$'\n'* ]] || die "Pipeline variable '$key' must be a single line"
  printf '%s=%s\n' "$key" "$val" >> "$PIPELINE_VARS_FILE"
}

# Mark a pipeline variable as secret without storing it as a value.
pipeline_secret() {
  pipeline_var "$1" "${2-}"
  if ! is_secret "$1"; then
    state_ensure_dirs
    printf '%s\n' "$1" >> "$DEVOPS_VALUES/.secret"
  fi
}

# Regenerates env/*.  Values first, then pipeline variables from every module.
env_generate() {
  state_ensure_dirs
  local tmp key file
  tmp=$(mktemp)
  PIPELINE_VARS_FILE=$tmp
  export PIPELINE_VARS_FILE
  local id
  for id in $MODULES; do
    module_hook "$id" module_env
  done
  pipeline_var DEVOPS_SCRIPTS "$(pipeline_scripts_dir)"
  deploys_application && pipeline_var ENVIRONMENTS "$(environments)"
  unset PIPELINE_VARS_FILE

  # Last definition wins, order of first appearance is kept.
  awk -F= '!seen[$1]++ { order[++n] = $1 } { val[$1] = substr($0, length($1) + 2) }
           END { for (i = 1; i <= n; i++) print order[i] "=" val[order[i]] }' "$tmp" > "$DEVOPS_ENV/pipeline.env"
  rm -f "$tmp"
  chmod 600 "$DEVOPS_ENV/pipeline.env"
  cut -d= -f1 "$DEVOPS_ENV/pipeline.env" > "$DEVOPS_ENV/pipeline.keys"

  ( umask 077
    {
      printf '# Generated by mvn-devops. Do not edit.\n'
      while IFS= read -r line; do
        key=${line%%=*}
        printf 'export %s=%q\n' "$key" "${line#*=}"
      done < "$DEVOPS_ENV/pipeline.env"
    } > "$DEVOPS_ENV/pipeline.sh"

    {
      printf 'DEVOPS_HOME=%s\n' "$(native_path "$DEVOPS_HOME")"
      printf 'DEVOPS_STATE=%s\n' "$(native_path "$DEVOPS_STATE")"
      printf 'PROJECT_DIR=%s\n' "$(native_path "$PROJECT_DIR")"
      printf 'PROJECT_NAME=%s\n' "$PROJECT_NAME"
      for file in "$DEVOPS_VALUES"/*; do
        [[ -f $file ]] || continue
        printf '%s=%s\n' "$(basename "$file")" "$(cat "$file")"
      done
      cat "$DEVOPS_ENV/pipeline.env"
    } > "$DEVOPS_ENV/compose.env"
  )
}

env_show() {
  local line key
  [[ -f "$DEVOPS_ENV/pipeline.env" ]] || die "No env generated yet. Run '$DEVOPS_CMD env'."
  while IFS= read -r line; do
    key=${line%%=*}
    printf '%s=%s\n' "$key" "$(mask "$key" "${line#*=}")"
  done < "$DEVOPS_ENV/pipeline.env"
}

# ---------------------------------------------------------------- URLs

# The machine Docker runs on, as reached from the machine running devops.sh
# and mvn.  localhost unless DOCKER_HOST (or the current docker context) points
# to another machine, e.g. DOCKER_HOST=ssh://user@build-vm.
docker_host_default() {
  local endpoint=${DOCKER_HOST:-} host
  if [[ -z $endpoint ]] && command -v docker > /dev/null; then
    endpoint=$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2> /dev/null || true)
  fi
  case $endpoint in
    ssh://*|tcp://*)
      host=${endpoint#*://}; host=${host#*@}; host=${host%%/*}
      if [[ $host == \[* ]]; then host=${host%%]*}]; else host=${host%%:*}; fi
      printf '%s' "$host" ;;
    *) printf 'localhost' ;;
  esac
}

devops_host() { value DEVOPS_HOST localhost; }

# ---------------------------------------------------------------- GitHub

# GITHUB_URL is https://github.com or a GitHub Enterprise Server.
github_url() { value GITHUB_URL https://github.com; }
github_host() { local u; u=$(github_url); u=${u#*://}; printf '%s' "${u%%/*}"; }
github_api_url() {
  if [[ $(github_host) == github.com ]]; then printf 'https://api.github.com'; else printf '%s/api/v3' "$(github_url)"; fi
}

# github_api <method> <path> [curl args]
github_api() {
  local method=$1 path=$2; shift 2
  curl -s -X "$method" -H "Authorization: Bearer $(value GITHUB_TOKEN)" \
    -H 'Accept: application/vnd.github+json' "$(github_api_url)$path" "$@"
}

# ---------------------------------------------------------------- servers

# Every tool with a server (SonarQube, Nexus, Jenkins, ...) either runs in
# Docker, started by devops.sh, or is an existing server somewhere else.
# <PREFIX>_SERVER_URL holds the URL of an existing server; empty means Docker.

# ask_server <PREFIX> <title> [example]
ask_server() {
  local key="${1}_SERVER_URL" url
  ask "$key" "$2: URL of an existing server${3:+ such as $3} (empty: run it in Docker)" ""
  url=$(value "$key")
  [[ $url == */ ]] && set_value "$key" "${url%/}"
  return 0
}

server_external() { [[ -n $(value "${1}_SERVER_URL") ]]; }

# server_url <PREFIX> <host port> [path]: URL devops.sh itself uses.
server_url() {
  if server_external "$1"; then
    printf '%s%s' "$(value "${1}_SERVER_URL")" "${3:-}"
  else
    host_url "$2" "${3:-}"
  fi
}

# server_pipeline_url <PREFIX> <service> <container port> <host port> [path]:
# URL the pipeline uses.
server_pipeline_url() {
  if server_external "$1"; then
    printf '%s%s' "$(value "${1}_SERVER_URL")" "${5:-}"
  else
    pipeline_url "$2" "$3" "$4" "${5:-}"
  fi
}

# Where does the pipeline run?  "host" (plain maven), "docker" (Jenkins or
# Concourse started by devops.sh) or "remote" (an existing Jenkins or Concourse
# server).  Only in Docker can the pipeline use container hostnames.
pipeline_url() {
  local service=$1 internal_port=$2 host_port=$3 path=${4:-}
  if [[ ${DEVOPS_RUNS_IN:-host} == docker ]]; then
    printf 'http://%s:%s%s' "$service" "$internal_port" "$path"
  else
    host_url "$host_port" "$path"
  fi
}

# URL used by the framework itself and by mvn with the maven orchestrator.
host_url() { printf 'http://%s:%s%s' "$(devops_host)" "$1" "${2:-}"; }
