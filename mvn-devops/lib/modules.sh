# shellcheck shell=bash
# Module discovery and hook execution.
#
# A module is a directory  modules/<category>/<name>/  containing:
#   module.conf   MODULE_TITLE, MODULE_DESCRIPTION, MODULE_REQUIRES, MODULE_RUNS_IN,
#                 MODULE_SERVER
#   module.sh     optional hook functions (see docs/module-guide.md)
#   compose.yml   optional docker compose fragment
#
# A category directory holds category.conf with CATEGORY_TITLE, CATEGORY_ORDER
# and CATEGORY_MODE (required | single | optional | multi).

DEVOPS_MODULES_DIR="$DEVOPS_HOME/modules"

# Categories sorted by CATEGORY_ORDER.
categories() {
  local dir
  for dir in "$DEVOPS_MODULES_DIR"/*/; do
    [[ -f "$dir/category.conf" ]] || continue
    (
      # shellcheck disable=SC1091
      source "$dir/category.conf"
      printf '%s %s\n' "${CATEGORY_ORDER:-99}" "$(basename "$dir")"
    )
  done | sort -n | awk '{print $2}'
}

category_conf() {
  # prints: MODE|TITLE
  (
    # shellcheck disable=SC1090
    source "$DEVOPS_MODULES_DIR/$1/category.conf"
    printf '%s|%s\n' "${CATEGORY_MODE:-multi}" "${CATEGORY_TITLE:-$1}"
  )
}

# Module ids (category/name) in a category.
category_modules() {
  local dir
  for dir in "$DEVOPS_MODULES_DIR/$1"/*/; do
    [[ -f "$dir/module.conf" ]] && printf '%s/%s\n' "$1" "$(basename "$dir")"
  done
}

all_modules() {
  local category
  for category in $(categories); do category_modules "$category"; done
}

module_dir() { printf '%s/%s' "$DEVOPS_MODULES_DIR" "$1"; }

module_exists() { [[ -f "$(module_dir "$1")/module.conf" ]]; }

# module_field <id> <VAR>
module_field() {
  (
    # shellcheck disable=SC1090
    source "$(module_dir "$1")/module.conf"
    printf '%s' "${!2:-}"
  )
}

# Resolve a short name ("jenkins") or full id ("orchestrator/jenkins").
module_resolve() {
  local name=$1 id
  if module_exists "$name"; then printf '%s' "$name"; return; fi
  for id in $(all_modules); do
    if [[ ${id#*/} == "$name" ]]; then printf '%s' "$id"; return; fi
  done
  return 1
}

# Order ids by category order, then add missing requirements.
modules_normalize() {
  local -a wanted=("$@") result=()
  local id req changed=1
  while (( changed )); do
    changed=0
    for id in "${wanted[@]}"; do
      for req in $(module_field "$id" MODULE_REQUIRES); do
        if [[ " ${wanted[*]} " != *" $req "* ]]; then
          wanted+=("$req"); changed=1
        fi
      done
    done
  done
  for id in $(all_modules); do
    [[ " ${wanted[*]} " == *" $id "* ]] && result+=("$id")
  done
  printf '%s' "${result[*]}"
}

# Run a hook of one module in a subshell, so modules cannot clash.
module_hook() {
  local id=$1 hook=$2; shift 2
  local dir
  dir=$(module_dir "$id")
  [[ -f "$dir/module.sh" ]] || return 0
  (
    MODULE_ID=$id
    MODULE_DIR=$dir
    export MODULE_ID MODULE_DIR
    # The deployment environments, in order (lib/environments.sh).
    # shellcheck disable=SC2034  # read by the modules
    ENVIRONMENTS=$(environments)
    # shellcheck disable=SC1091
    source "$dir/module.conf"
    # shellcheck disable=SC1091
    source "$dir/module.sh"
    if declare -F "$hook" > /dev/null; then
      "$hook" "$@"
    fi
  )
}

module_has_hook() {
  local dir
  dir=$(module_dir "$1")
  [[ -f "$dir/module.sh" ]] && grep -Eq "^[[:space:]]*$2[[:space:]]*\(\)" "$dir/module.sh"
}

# Runs a hook for every selected module, in profile order.
modules_hook() {
  local hook=$1 id; shift
  for id in $MODULES; do
    if module_has_hook "$id" "$hook"; then
      log_step "$(module_field "$id" MODULE_TITLE): ${hook#module_}"
      module_hook "$id" "$hook" "$@"
    fi
  done
}

# Selected modules that ship a compose fragment, except those using an
# existing server (MODULE_SERVER prefix with a <PREFIX>_SERVER_URL).  A
# module's prepare hook may add to its fragment what depends on the project
# in $(module_compose_extra <id>), e.g. one published port per environment.
compose_files() {
  local id server
  for id in $MODULES; do
    [[ -f "$(module_dir "$id")/compose.yml" ]] || continue
    server=$(module_field "$id" MODULE_SERVER)
    [[ -n $server ]] && server_external "$server" && continue
    printf '%s\n' "$(module_dir "$id")/compose.yml"
    [[ -f "$(module_compose_extra "$id")" ]] && printf '%s\n' "$(module_compose_extra "$id")"
  done
  return 0
}

module_compose_extra() { printf '%s/compose/%s.yml' "$DEVOPS_GENERATED" "${1//\//-}"; }
