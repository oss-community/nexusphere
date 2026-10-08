# shellcheck shell=bash
# Pipeline stages.
#
# Every module may contribute stages from its module_stages hook:
#
#   stage <order> <phase> <name> "<maven arguments>"
#
#   order  number, stages of a phase run in ascending order
#   phase  ci (build, verify), cd (publish the image and other artifacts) or
#          the name of a deployment environment (lib/environments.sh); "prod"
#          stands for the last environment.  Phases run in that order: ci, cd,
#          then the environments in the order of ENVIRONMENTS.  An
#          environment that needs approval runs only after someone approves.
#   name   short id, letters, digits and dashes; unique in the pipeline
#   args   arguments passed to mvn; may use $VARS from the pipeline env
#
#   shell_stage <order> <phase> <name> "<shell command>"
#
# runs a POSIX shell command in the project root instead of mvn, for the rare
# step Maven cannot do from the command line.
#
# The orchestrator turns the ordered list into its own format (a shell
# script, a Jenkinsfile, a Concourse pipeline).

stage() {
  local order=$1 phase=$2 name=$3 args=$4
  [[ -n ${STAGES_FILE:-} ]] || die "stage used outside module_stages"
  [[ $order =~ ^[0-9]+$ ]] || die "stage '$name': order must be a number"
  if [[ $phase != ci && $phase != cd ]]; then
    phase=$(env_resolve "$phase") || die "stage '$name': phase must be ci, cd or an environment ($(environments))"
  fi
  [[ $name =~ ^[a-z0-9-]+$ ]] || die "stage '$name': use lowercase letters, digits and dashes"
  # Arguments are embedded in single-quoted strings by the orchestrators.
  case $args in
    *'|'* | *"'"* | *\\* | *'${'*) die "stage '$name': arguments may not contain | ' \\ or \${" ;;
  esac
  printf '%s|%s|%s|%s|%s\n' "$(phase_rank "$phase")" "$order" "$phase" "$name" "$args" >> "$STAGES_FILE"
}

# phase_rank <phase>: position of a phase in the pipeline.
phase_rank() {
  local env rank=2
  case $1 in
    ci) printf 0; return ;;
    cd) printf 1; return ;;
  esac
  for env in $(environments); do
    [[ $env == "$1" ]] && break
    rank=$((rank + 1))
  done
  printf '%s' "$rank"
}

# phase_group <phase>: the part of the pipeline a phase belongs to: ci, cd
# (which includes the environments before the first approval) or the
# environment whose approval starts it.
phase_group() {
  case $1 in
    ci|cd) printf '%s' "$1" ;;
    *) env_group "$1" ;;
  esac
}

# Environments that wait for approval and have stages, in order.
pipeline_gates() {
  local phases gate
  phases=" $(pipeline_stages | cut -d'|' -f2 | sort -u | xargs) "
  for gate in $(env_gates); do
    [[ $phases == *" $gate "* ]] && printf '%s\n' "$gate"
  done
  return 0
}

# pipeline_phase <phase>: ci, cd or the environment a phase names ("prod"
# is the last one).
pipeline_phase() {
  case $1 in
    ''|ci|cd) printf '%s' "$1" ;;
    *) env_resolve "$1" || die "--phase must be ci, cd or an environment ($(environments))" ;;
  esac
}

# pipeline_select <phase> <from stage> <only stage>: the stages a run of the
# maven orchestrators runs, as "phase|name|args" lines in SELECTED, and in
# HELD the approvals it stops before.
#   no phase  ci, cd and the environments before the first approval
#   ci        the ci stages only
#   cd        what follows ci up to the first approval
#   <env>     the approval of an environment: it and the environments after
#             it up to the next approval
# A stage named with "only" runs whatever its phase.
pipeline_select() {
  local phase=$1 from=$2 only=$3 started=0 group='' line stage_phase stage_group name
  local -a stages=()
  SELECTED=()
  HELD=()
  [[ -z $from ]] && started=1
  [[ -n $phase && $phase != ci && $phase != cd ]] && group=$(phase_group "$phase")
  mapfile -t stages < <(pipeline_stages)
  for line in "${stages[@]}"; do
    IFS="|" read -r _ stage_phase name _ <<< "$line"
    [[ $name == "$from" ]] && started=1
    (( started )) || continue
    [[ -n $only && $name != "$only" ]] && continue
    stage_group=$(phase_group "$stage_phase")
    if [[ -n $only ]]; then
      :
    elif [[ $phase == ci ]]; then
      [[ $stage_phase == ci ]] || continue
    elif [[ $phase == cd ]]; then
      [[ $stage_phase != ci && $stage_group == cd ]] || continue
    elif [[ -n $phase ]]; then
      [[ $stage_group == "$group" && $(phase_rank "$stage_phase") -ge $(phase_rank "$phase") ]] || continue
    elif [[ $stage_group != ci && $stage_group != cd ]]; then
      # Stages behind an approval run only when asked for: that is the approval.
      [[ " ${HELD[*]} " == *" $stage_group "* ]] || HELD+=("$stage_group")
      continue
    fi
    SELECTED+=("${line#*|}")
  done
  (( started )) || die "No stage named '$from'. See '$DEVOPS_CMD stages'."
  (( ${#SELECTED[@]} )) || die "No stage matched. See '$DEVOPS_CMD stages'."
  return 0
}

# pipeline_next <phase>: after a run of the maven orchestrators, tells which
# approval comes next.
pipeline_next() {
  local gate
  if [[ -z $1 ]]; then
    (( ${#HELD[@]} )) && log_info "${HELD[0]} waits for approval: '$DEVOPS_CMD run --phase ${HELD[0]}'."
    return 0
  fi
  [[ $1 == ci || $1 == cd ]] && return 0
  for gate in $(pipeline_gates); do
    if (( $(phase_rank "$gate") > $(phase_rank "$1") )); then
      log_info "$gate waits for approval: '$DEVOPS_CMD run --phase $gate'."
      return 0
    fi
  done
}

# Shell stages are stored with a leading "!".
shell_stage() {
  stage "$1" "$2" "$3" "!$4"
}

# stage_command <maven flags> <args>: the command an orchestrator runs.
stage_command() {
  if [[ $2 == '!'* ]]; then
    printf '%s' "${2#!}"
  else
    printf 'mvn %s %s' "$1" "$2"
  fi
}

# Prints "order|phase|name|args" lines for the selected modules.
pipeline_stages() {
  local tmp id
  tmp=$(mktemp)
  STAGES_FILE=$tmp
  export STAGES_FILE
  for id in $MODULES; do
    module_hook "$id" module_stages
  done
  unset STAGES_FILE
  sort -t'|' -k1,1n -k2,2n "$tmp" | cut -d'|' -f2-
  rm -f "$tmp"
}

# Where CI containers write the framework's settings file (see pipeline_ci_setup).
CI_SETTINGS='$HOME/mvn-devops-settings.xml'

# Maven flags shared by every orchestrator.
#   $1  project root as the orchestrator sees it ("" for the current directory)
#   $2  path of the framework settings file (templates/settings.xml), passed as
#       global settings so the project's own settings file can still be used
maven_flags() {
  local root=${1:-} global=$2 settings profiles flags='-B'
  settings=$(value MAVEN_SETTINGS)
  profiles=$(value MAVEN_PROFILES)
  [[ -n $root ]] && flags+=" -f $(printf '%q' "$root/pom.xml")"
  flags+=" -gs $global"
  if [[ -n $settings ]]; then
    if [[ -n $root ]]; then flags+=" -s $(printf '%q' "$root/$settings")"; else flags+=" -s $settings"; fi
  fi
  [[ -n $profiles ]] && flags+=" -P $profiles"
  printf '%s' "$flags"
}

pipeline_print() {
  local order phase name args shown=''
  printf '%-6s %-12s %-24s %s\n' ORDER PHASE STAGE 'MAVEN ARGUMENTS'
  while IFS='|' read -r order phase name args; do
    if [[ $phase != "$shown" && $(phase_group "$phase") == "$phase" ]] && is_environment "$phase"; then
      printf '%-6s %-12s %-24s %s\n' - "$phase" approval "waits until someone approves $phase"
    fi
    shown=$phase
    printf '%-6s %-12s %-24s %s\n' "$order" "$phase" "$name" "$args"
  done < <(pipeline_stages)
}

# Scripts that stages call as "$DEVOPS_SCRIPTS/<name>" (templates/scripts).
# CI containers get a copy in .devops/scripts of the checkout, and of the Helm
# chart in .devops/helm.
pipeline_scripts_dir() {
  if [[ ${DEVOPS_RUNS_IN:-host} == host ]]; then
    printf '%s' "$DEVOPS_HOME/templates/scripts"
  else
    printf '.devops/scripts'
  fi
}

# One shell line that prepares a CI container: writes the framework settings
# file to $CI_SETTINGS, the stage scripts to .devops/scripts and the Helm
# chart to .devops/helm, sets the git identity and installs the GitHub deploy
# key when the site module provided one.
pipeline_ci_setup() {
  printf 'echo %s | base64 -d > %s; ' "$(base64 < "$DEVOPS_HOME/templates/settings.xml" | tr -d '\n')" "$CI_SETTINGS"
  printf 'mkdir -p .devops; echo %s | base64 -d | tar -xzf - -C .devops; ' \
    "$(COPYFILE_DISABLE=1 tar -czf - -C "$DEVOPS_HOME/templates" scripts helm | base64 | tr -d '\n')"
  printf '%s' 'git config --global user.name "$GITHUB_USERNAME"; git config --global user.email "$GITHUB_EMAIL"; '
  printf '%s' 'if [ -n "$GITHUB_DEPLOY_KEY_B64" ]; then mkdir -p ~/.ssh && chmod 700 ~/.ssh; '
  printf '%s' 'echo "$GITHUB_DEPLOY_KEY_B64" | base64 -d > ~/.ssh/id_ed25519; chmod 600 ~/.ssh/id_ed25519; '
  printf '%s\n' 'ssh-keyscan "$GITHUB_HOST" >> ~/.ssh/known_hosts 2>/dev/null; fi'
}

# Pipeline variables that are secrets (masked by the orchestrators).
pipeline_secret_keys() {
  local key
  while IFS= read -r key; do
    is_secret "$key" && printf '%s\n' "$key"
  done < "$DEVOPS_ENV/pipeline.keys"
  return 0
}
