# shellcheck shell=bash
# Ready-made pipelines: pipelines/<name>.conf in mvn-devops, or a file of the
# project's own in the same format.
#
# A ready-made pipeline is a devops.conf without the project: the
# orchestrator, the tools and any answer that is neither secret nor personal.
#
#   # Maven, SonarQube and Nexus: build, analyse and publish the artifacts.
#   ORCHESTRATOR=maven
#   TOOLS=sonarqube,nexus
#   ENVIRONMENTS=dev test staging prod
#
# The first comment line describes it.  "init --pipeline <name or file>"
# selects its tools and writes its answers to devops.conf, with PIPELINE=<name>.
# "secrets" then asks nothing that has a default; passwords, tokens and keys
# are generated for this project.  Only what cannot be generated is asked,
# such as the GitHub user and token.

DEVOPS_PRESETS_DIR="$DEVOPS_HOME/pipelines"

# Names of the ready-made pipelines.
preset_names() {
  local file
  for file in "$DEVOPS_PRESETS_DIR"/*.conf; do
    [[ -f $file ]] && basename "$file" .conf
  done
  return 0
}

# preset_file <name or file>: the file of a ready-made pipeline.
preset_file() {
  if [[ -f $1 ]]; then
    printf '%s' "$1"
  elif [[ -f "$DEVOPS_PRESETS_DIR/$1.conf" ]]; then
    printf '%s' "$DEVOPS_PRESETS_DIR/$1.conf"
  else
    die "No ready-made pipeline '$1'. See '$DEVOPS_CMD pipelines'."
  fi
}

# preset_name <name or file>
preset_name() { local name; name=$(basename "$1"); printf '%s' "${name%.conf}"; }

# preset_lines <file>: its KEY=value lines, without comments and blank lines.
preset_lines() {
  local line
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%"$CR"}
    [[ $line =~ ^[A-Z][A-Z0-9_]*= ]] && printf '%s\n' "$line"
  done < "$1"
  return 0
}

# preset_get <file> <KEY>
preset_get() {
  local line
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%"$CR"}
    [[ $line == "$2="* ]] && { printf '%s' "${line#*=}"; return 0; }
  done < "$1"
  return 1
}

preset_description() {
  local line
  while IFS= read -r line || [[ -n $line ]]; do
    line=${line%"$CR"}
    if [[ $line == '# '* ]]; then printf '%s' "${line#'# '}"; return; fi
  done < "$1"
}

# preset_apply <file>: writes its answers to devops.conf (init has selected
# the tools already).
preset_apply() {
  local line key
  while IFS= read -r line; do
    key=${line%%=*}
    case $key in
      ORCHESTRATOR|TOOLS|MODULES|PROJECT_NAME|PIPELINE) continue ;;
    esac
    conf_set "$key" "${line#*=}"
  done < <(preset_lines "$1")
  conf_set PIPELINE "$(preset_name "$1")"
}

# True when the project was set up from a ready-made pipeline.
preset_selected() { conf_get PIPELINE > /dev/null 2>&1; }

cmd_pipelines() {
  local name file
  printf '%sReady-made pipelines%s (devops.sh init --pipeline <name>, or setup --pipeline <name>)\n' "$C_BOLD" "$C_RESET"
  for name in $(preset_names); do
    file="$DEVOPS_PRESETS_DIR/$name.conf"
    printf '\n  %s%s%s\n    %s\n    %s with %s\n' "$C_BOLD" "$name" "$C_RESET" "$(preset_description "$file")" \
      "$(preset_get "$file" ORCHESTRATOR)" "$(preset_get "$file" TOOLS | tr ',' ' ')"
    if preset_get "$file" ENVIRONMENTS > /dev/null; then
      printf '    environments: %s\n' "$(preset_get "$file" ENVIRONMENTS)"
    fi
  done
}
