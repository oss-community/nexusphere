# shellcheck shell=bash
# Pipeline stages.
#
# Every module may contribute stages from its module_stages hook:
#
#   stage <order> <phase> <name> "<maven arguments>"
#
#   order  number, stages run in ascending order
#   phase  ci (build, verify) or cd (publish, deploy)
#   name   short id, letters, digits and dashes
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
  [[ $phase == ci || $phase == cd ]] || die "stage '$name': phase must be ci or cd"
  [[ $name =~ ^[a-z0-9-]+$ ]] || die "stage '$name': use lowercase letters, digits and dashes"
  # Arguments are embedded in single-quoted strings by the orchestrators.
  case $args in
    *'|'* | *"'"* | *\\* | *'${'*) die "stage '$name': arguments may not contain | ' \\ or \${" ;;
  esac
  printf '%s|%s|%s|%s\n' "$order" "$phase" "$name" "$args" >> "$STAGES_FILE"
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
  sort -t'|' -k1,1n "$tmp"
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
  local order phase name args
  printf '%-6s %-4s %-16s %s\n' ORDER PHASE STAGE 'MAVEN ARGUMENTS'
  while IFS='|' read -r order phase name args; do
    printf '%-6s %-4s %-16s %s\n' "$order" "$phase" "$name" "$args"
  done < <(pipeline_stages)
}

# One shell line that prepares a CI container: writes the framework settings
# file to $CI_SETTINGS, sets the git identity and installs the GitHub deploy key
# when the site module provided one.
pipeline_ci_setup() {
  printf 'echo %s | base64 -d > %s; ' "$(base64 < "$DEVOPS_HOME/templates/settings.xml" | tr -d '\n')" "$CI_SETTINGS"
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
