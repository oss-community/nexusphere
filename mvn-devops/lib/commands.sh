# shellcheck shell=bash
# Implementation of the devops.sh commands.

load_project() {
  profile_load
  DEVOPS_RUNS_IN=$(module_field "orchestrator/$ORCHESTRATOR" MODULE_RUNS_IN)
  DEVOPS_RUNS_IN=${DEVOPS_RUNS_IN:-host}
  local server
  server=$(module_field "orchestrator/$ORCHESTRATOR" MODULE_SERVER)
  if [[ -n $server ]] && server_external "$server"; then DEVOPS_RUNS_IN=remote; fi
  export DEVOPS_RUNS_IN
}

orchestrator_id() { printf 'orchestrator/%s' "$ORCHESTRATOR"; }

# ---------------------------------------------------------------- init

# menu_pick <category> <mode> <title> <current selection...>
# Prints the chosen ids.
menu_pick() {
  local category=$1 mode=$2 title=$3; shift 3
  local -a options=() current=("$@") picked=()
  local id i answer default='' num
  mapfile -t options < <(category_modules "$category")
  (( ${#options[@]} )) || return 0

  if [[ $mode == required ]]; then
    printf '%s\n' "${options[@]}"
    return
  fi

  {
    printf '\n%s%s%s' "$C_BOLD" "$title" "$C_RESET"
    [[ $mode == single ]] && printf ' (choose one)\n' || printf ' (choose any, comma separated, 0 for none)\n'
    for i in "${!options[@]}"; do
      id=${options[$i]}
      printf '  %d) %-16s %s\n' $((i + 1)) "${id#*/}" "$(module_field "$id" MODULE_DESCRIPTION)"
      if [[ " ${current[*]} " == *" $id "* ]]; then default+="${default:+,}$((i + 1))"; fi
    done
  } >&2
  if [[ -z $default ]]; then
    if [[ $mode == single ]]; then default=1; else default=0; fi
  fi

  while true; do
    if [[ ${DEVOPS_DEFAULTS:-0} == 1 ]]; then
      answer=$default
    else
      read -r -p "  > [$default]: " answer || true
      answer=${answer:-$default}
    fi
    picked=()
    local ok=1
    IFS=', ' read -r -a nums <<< "$answer"
    for num in "${nums[@]}"; do
      [[ -z $num || $num == 0 ]] && continue
      if [[ $num =~ ^[0-9]+$ ]] && (( num >= 1 && num <= ${#options[@]} )); then
        picked+=("${options[$((num - 1))]}")
      else
        ok=0
      fi
    done
    if [[ $mode == single ]] && (( ${#picked[@]} != 1 )); then ok=0; fi
    (( ok )) && break
    log_warn "Invalid choice '$answer'."
    [[ ${DEVOPS_DEFAULTS:-0} == 1 ]] && die "Default selection for $title is invalid"
  done
  (( ${#picked[@]} )) && printf '%s\n' "${picked[@]}"
  return 0
}

cmd_init() {
  local orchestrator='' with='' name='' id category mode title conf
  local -a selected=() current=()
  while (( $# )); do
    case $1 in
      --orchestrator) orchestrator=$2; shift 2 ;;
      --with) with=$2; shift 2 ;;
      --name) name=$2; shift 2 ;;
      *) die "init: unknown option $1" ;;
    esac
  done

  if profile_exists; then
    # shellcheck disable=SC1090
    source "$DEVOPS_PROFILE"
    read -r -a current <<< "${MODULES:-}"
  fi
  PROJECT_NAME=${name:-${PROJECT_NAME:-$(basename "$PROJECT_DIR")}}

  log_step "mvn-devops: choose the tools for $PROJECT_NAME"
  log_dim "Project directory: $PROJECT_DIR"

  if [[ -n $orchestrator || -n $with ]]; then
    [[ -n $orchestrator ]] || die "--with needs --orchestrator"
    id="orchestrator/${orchestrator#orchestrator/}"
    module_exists "$id" || die "Unknown orchestrator '$orchestrator'"
    selected+=("$id")
    IFS=',' read -r -a names <<< "$with"
    for name in "${names[@]}"; do
      [[ -z $name ]] && continue
      id=$(module_resolve "$name") || die "Unknown module '$name'. See '$DEVOPS_CMD modules'."
      [[ $id == orchestrator/* ]] && die "Only one orchestrator may be selected"
      selected+=("$id")
    done
    for category in $(categories); do
      conf=$(category_conf "$category")
      [[ ${conf%%|*} == required ]] && mapfile -t -O "${#selected[@]}" selected < <(category_modules "$category")
    done
  else
    for category in $(categories); do
      conf=$(category_conf "$category")
      mode=${conf%%|*}; title=${conf#*|}
      mapfile -t -O "${#selected[@]}" selected < <(menu_pick "$category" "$mode" "$title" "${current[@]}")
    done
  fi

  MODULES=$(modules_normalize "${selected[@]}")
  ORCHESTRATOR=''
  for id in $MODULES; do
    [[ $id == orchestrator/* ]] && ORCHESTRATOR=${id#*/}
  done
  [[ -n $ORCHESTRATOR ]] || die "An orchestrator must be selected"
  profile_save

  log_step "Selected modules"
  for id in $MODULES; do
    printf '  %-26s %s\n' "$id" "$(module_field "$id" MODULE_TITLE)"
  done
  log_ok "Saved $DEVOPS_PROFILE"
  log_info "Next: '$DEVOPS_CMD setup' (or the single steps: secrets, up, configure, render, publish)."
}

# ---------------------------------------------------------------- lifecycle

# shellcheck disable=SC2120  # optional --reconfigure; setup calls it without arguments
cmd_secrets() {
  [[ ${1:-} == --reconfigure ]] && export DEVOPS_RECONFIGURE=1
  load_project
  state_ensure_dirs
  log_step "Docker machine"
  ask DEVOPS_HOST "Address of the machine Docker runs on, for the tools started in Docker" "$(docker_host_default)"
  modules_hook module_secrets
  env_generate
  log_ok "Values stored in $DEVOPS_VALUES"
}

cmd_up() {
  load_project
  env_generate
  modules_hook module_prepare
  env_generate
  if compose_files | grep -q /orchestrator/jenkins/ && [[ $(docker_host_default) != localhost ]]; then
    log_warn "Jenkins mounts files from $DEVOPS_STATE, which a Docker daemon on another machine cannot see. Run devops.sh on the Docker machine instead."
  fi
  compose_export > /dev/null
  log_step "Starting containers"
  compose up -d --build --remove-orphans
}

cmd_configure() {
  load_project
  local id
  for id in $MODULES; do
    [[ $id == orchestrator/* ]] && continue
    if module_has_hook "$id" module_configure; then
      log_step "$(module_field "$id" MODULE_TITLE): configure"
      module_hook "$id" module_configure
    fi
  done
  env_generate
  log_ok "Tools configured"
}

cmd_env() {
  load_project
  env_generate
  case ${1:-} in
    --show) env_show ;;
    --windows) env_windows ;;
    '') log_ok "Generated $DEVOPS_ENV" ;;
    *) die "env: unknown option $1" ;;
  esac
}

# Writes a .bat file that stores the pipeline variables as Windows user
# environment variables, for running maven from an IDE on Windows.
env_windows() {
  local out="$DEVOPS_GENERATED/set-env.bat" line key val
  {
    printf '@echo off\r\n'
    printf 'rem Generated by mvn-devops. Stores pipeline variables for the current Windows user.\r\n'
    while IFS= read -r line; do
      key=${line%%=*}; val=${line#*=}
      val=${val//%/%%}
      printf 'setx %s "%s" >nul\r\n' "$key" "$val"
    done < "$DEVOPS_ENV/pipeline.env"
    printf 'echo Done. Open a new console or restart the IDE to pick up the variables.\r\n'
  } > "$out"
  log_ok "Wrote $out (contains secrets, keep it private)"
}

cmd_get() {
  (( $# == 1 )) || die "Usage: $DEVOPS_CMD get <KEY>"
  has_value "$1" || die "No value named '$1'"
  value "$1"; printf '\n'
}

cmd_stages() {
  load_project
  pipeline_print
}

cmd_render() {
  load_project
  env_generate
  module_hook "$(orchestrator_id)" module_render
}

cmd_publish() {
  load_project
  env_generate
  if [[ $DEVOPS_RUNS_IN == remote && $(devops_host) == localhost && -n $(compose_files) ]]; then
    log_warn "The CI server reaches the tools started in Docker at localhost. Set DEVOPS_HOST to an"
    log_warn "address it can reach with '$DEVOPS_CMD secrets --reconfigure'."
  fi
  module_hook "$(orchestrator_id)" module_render
  module_hook "$(orchestrator_id)" module_configure
  module_hook "$(orchestrator_id)" module_publish
}

cmd_run() {
  load_project
  env_generate
  module_hook "$(orchestrator_id)" module_run "$@"
}

cmd_setup() {
  # Options are passed to init (e.g. --orchestrator jenkins --with sonarqube,nexus).
  if ! profile_exists; then
    cmd_init "$@"
  fi
  cmd_secrets
  cmd_up
  cmd_configure
  cmd_publish
  log_step "Setup finished"
  log_info "Run the pipeline with '$DEVOPS_CMD run'."
  cmd_urls
}

# ---------------------------------------------------------------- operations

cmd_status() {
  load_project
  log_step "Profile"
  printf '  project       %s\n  orchestrator  %s\n  modules       %s\n' "$PROJECT_NAME" "$ORCHESTRATOR" "$MODULES"
  log_step "Containers"
  compose ps
}

cmd_urls() {
  load_project
  local id
  log_step "Web consoles"
  for id in $MODULES; do module_hook "$id" module_urls; done
}

cmd_logs() { load_project; compose logs -f --tail 200 "$@"; }

# Pass-through to docker compose with the project's files and env.
cmd_compose() { load_project; env_generate; compose "$@"; }

cmd_export_compose() { load_project; env_generate; compose_export "$@"; }

cmd_down() { load_project; compose down; }

cmd_destroy() {
  load_project
  confirm "Remove all containers AND their data volumes for $PROJECT_NAME?" n || { log_info "Cancelled."; return; }
  modules_hook module_destroy
  compose down --volumes --remove-orphans
  if confirm "Also delete stored values and generated files in $DEVOPS_STATE?" n; then
    rm -rf "$DEVOPS_STATE"
    log_ok "Deleted $DEVOPS_STATE"
  fi
}

cmd_modules() {
  local category conf id
  for category in $(categories); do
    conf=$(category_conf "$category")
    printf '\n%s%s%s (%s)\n' "$C_BOLD" "${conf#*|}" "$C_RESET" "${conf%%|*}"
    for id in $(category_modules "$category"); do
      printf '  %-16s %s\n' "${id#*/}" "$(module_field "$id" MODULE_DESCRIPTION)"
    done
  done
}

cmd_doctor() {
  local tool ok=1
  log_step "Checking tools"
  for tool in bash curl jq git java mvn docker ssh-keygen; do
    if command -v "$tool" > /dev/null; then
      log_ok "$tool"
    else
      log_warn "$tool not found"; ok=0
    fi
  done
  if command -v docker > /dev/null; then
    if docker compose version > /dev/null 2>&1; then log_ok "docker compose"; else log_warn "docker compose plugin not found"; ok=0; fi
    if docker info > /dev/null 2>&1; then log_ok "docker daemon reachable on $(docker_host_default)"; else log_warn "docker daemon is not running"; ok=0; fi
  fi
  # Only site publishing with the maven orchestrator needs your own SSH key.
  if command -v ssh > /dev/null; then
    if ssh -T -o BatchMode=yes -o ConnectTimeout=5 "git@$(github_host)" 2>&1 | grep -q 'successfully authenticated'; then
      log_ok "SSH key accepted by $(github_host)"
    else
      log_warn "No SSH key accepted by $(github_host); needed only to publish the site from this machine (docs/github-setup.md)"
    fi
  fi
  if (( ok )); then log_ok "All good"; else log_warn "Some tools are missing; see docs/prerequisites.md"; fi
}
