#!/usr/bin/env bash
# mvn-devops: pick your DevOps tools, then set up and run a Maven pipeline.
set -euo pipefail

# Follow symlinks, e.g. /usr/bin/mvn-devops -> /usr/share/mvn-devops/devops.sh.
devops_source=${BASH_SOURCE[0]}
while [[ -L $devops_source ]]; do
  devops_link=$(readlink "$devops_source")
  [[ $devops_link == /* ]] || devops_link="$(dirname "$devops_source")/$devops_link"
  devops_source=$devops_link
done
DEVOPS_HOME="$(cd "$(dirname "$devops_source")" && pwd)"
unset devops_source devops_link
DEVOPS_CMD="$(basename "$0")"
export DEVOPS_HOME DEVOPS_CMD

# shellcheck source=lib/log.sh
source "$DEVOPS_HOME/lib/log.sh"
# shellcheck source=lib/state.sh
source "$DEVOPS_HOME/lib/state.sh"
# shellcheck source=lib/modules.sh
source "$DEVOPS_HOME/lib/modules.sh"
# shellcheck source=lib/maven.sh
source "$DEVOPS_HOME/lib/maven.sh"
# shellcheck source=lib/pipeline.sh
source "$DEVOPS_HOME/lib/pipeline.sh"
# shellcheck source=lib/docker.sh
source "$DEVOPS_HOME/lib/docker.sh"
# shellcheck source=lib/commands.sh
source "$DEVOPS_HOME/lib/commands.sh"
# shellcheck source=lib/release.sh
source "$DEVOPS_HOME/lib/release.sh"

usage() {
  cat <<EOF
Usage: $DEVOPS_CMD [options] <command> [args]

Setup
  init         choose orchestrator and tools (interactive menu)
                 --orchestrator <name> --with <a,b,c>   non-interactive
  setup        init (if needed) + secrets + up + configure + publish
  secrets      ask for the values each selected tool needs  [--reconfigure]
  up           prepare and start the tool containers
  configure    finish tool setup after start (admin passwords, tokens)
  publish      render the pipeline and install it in the orchestrator

Pipeline
  stages       show the stages contributed by the selected tools
  render       generate the pipeline files into .devops/generated
  run          run the pipeline  (maven: [--dry-run] [--from s] [--only s] [--phase ci|cd])
  release      release version, deploy, next snapshot, push
                 [--version X] [--next Y-SNAPSHOT] [--dry-run] [--no-push]

Operations
  status | urls | logs [service] | down | destroy
  compose ...  run docker compose with the project's files (e.g. compose ps)
  export-compose [dir]  write one docker-compose.yml + .env of the selected tools
                        (default: .devops/compose; also written by up)
  env          regenerate env files  [--show | --windows]
  get <KEY>    print one stored value (e.g. an admin password)
  modules      list available modules
  doctor       check prerequisites

Options
  -p, --project <dir>   Maven project directory (default: current directory)
  -y, --yes             accept defaults for every question
  -h, --help            show this help
  -v, --version         show the version
EOF
}

main() {
  PROJECT_DIR=$PWD
  while (( $# )); do
    case $1 in
      -p|--project) PROJECT_DIR=$2; shift 2 ;;
      -y|--yes) export DEVOPS_DEFAULTS=1; shift ;;
      -h|--help) usage; return ;;
      -v|--version) cat "$DEVOPS_HOME/VERSION"; return ;;
      -*) die "Unknown option $1" ;;
      *) break ;;
    esac
  done
  (( $# )) || { usage; return; }

  [[ -d $PROJECT_DIR ]] || die "Project directory '$PROJECT_DIR' does not exist"
  PROJECT_DIR=$(cd "$PROJECT_DIR" && pwd)
  export PROJECT_DIR
  state_init_paths

  local command=$1; shift
  case $command in
    init|setup|secrets|up|configure|publish|stages|render|run|status|urls|logs|down|destroy|compose|env|get|modules|doctor|release)
      "cmd_$command" "$@" ;;
    export-compose) cmd_export_compose "$@" ;;
    help) usage ;;
    *) usage; die "Unknown command '$command'" ;;
  esac
}

main "$@"
