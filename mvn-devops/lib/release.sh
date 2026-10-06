# shellcheck shell=bash
# devops.sh release: a Maven release without maven-release-plugin, so the pom
# needs no <scm> or <distributionManagement>.
#
#   1. set the release version (1.2.0-SNAPSHOT -> 1.2.0) in every module
#   2. commit "Release 1.2.0" and tag v1.2.0
#   3. run the deploy stages of the selected artifact repositories
#   4. set the next development version (1.2.1-SNAPSHOT) and commit it
#   5. push the branch and the tag
#
# Nothing is pushed before step 5; when a step fails the local commits and the
# tag are rolled back.  Runs on this machine with mvn, git and your SSH key or
# git credentials.

# next_snapshot 1.2.0 -> 1.2.1-SNAPSHOT
next_snapshot() {
  local version=$1 last
  if [[ $version =~ ^(.*[^0-9])?([0-9]+)$ ]]; then
    last=${BASH_REMATCH[2]}
    printf '%s%s-SNAPSHOT' "${BASH_REMATCH[1]}" "$((10#$last + 1))"
  else
    printf '%s.1-SNAPSHOT' "$version"
  fi
}

cmd_release() {
  local version='' next='' dry=0 push=1 flags current start tag line name args ran=0
  while (( $# )); do
    case $1 in
      --version) version=$2; shift 2 ;;
      --next) next=$2; shift 2 ;;
      --dry-run) dry=1; shift ;;
      --no-push) push=0; shift ;;
      *) die "release: unknown option $1 (use --version X, --next Y-SNAPSHOT, --dry-run, --no-push)" ;;
    esac
  done
  load_project
  # The release runs here, so the tools are reached as from this machine.
  [[ $DEVOPS_RUNS_IN == remote ]] || DEVOPS_RUNS_IN=host
  export DEVOPS_RUNS_IN
  env_generate
  command -v mvn > /dev/null || die "mvn is not installed"
  git -C "$PROJECT_DIR" rev-parse --git-dir > /dev/null 2>&1 || die "$PROJECT_DIR is not a git repository"
  [[ -z $(git -C "$PROJECT_DIR" status --porcelain --untracked-files=no) ]] \
    || die "The working tree has uncommitted changes. Commit or stash them first."

  flags=$(maven_flags "$PROJECT_DIR" "$(printf '%q' "$DEVOPS_HOME/templates/settings.xml")")
  current=$(cd "$PROJECT_DIR" && eval "mvn $flags -q $(mvn_evaluate) -Dexpression=project.version -DforceStdout")
  [[ -n $current ]] || die "Could not read the project version."
  version=${version:-${current%-SNAPSHOT}}
  next=${next:-$(next_snapshot "$version")}
  tag="v$version"
  git -C "$PROJECT_DIR" rev-parse -q --verify "refs/tags/$tag" > /dev/null && die "Tag $tag already exists."

  local -a deploys=()
  while IFS= read -r line; do
    IFS='|' read -r _ _ name args <<< "$line"
    [[ $name == deploy-* ]] && deploys+=("$name|$args")
  done < <(pipeline_stages)
  (( ${#deploys[@]} )) || log_warn "No artifact repository is selected; the release is only tagged."

  log_step "Release $current -> $version, then $next"
  log_dim "  branch $(git -C "$PROJECT_DIR" rev-parse --abbrev-ref HEAD), tag $tag, deploy: ${deploys[*]%%|*}"
  if (( dry )); then
    log_dim "  mvn $flags $(mvn_versions_set) -DnewVersion=$version -DprocessAllModules=true -DgenerateBackupPoms=false"
    for line in "${deploys[@]}"; do log_dim "  $(stage_command "$flags" "${line#*|}")"; done
    log_dim "  mvn $flags $(mvn_versions_set) -DnewVersion=$next ..."
    (( push )) && log_dim "  git push origin HEAD $tag"
    return 0
  fi
  confirm "Release $version?" y || { log_info "Cancelled."; return 0; }

  start=$(git -C "$PROJECT_DIR" rev-parse HEAD)
  release_rollback() {
    log_warn "Rolling back to $start"
    git -C "$PROJECT_DIR" reset -q --hard "$start"
    git -C "$PROJECT_DIR" tag -d "$tag" > /dev/null 2>&1 || true
  }
  release_step() {
    ( source "$DEVOPS_ENV/pipeline.sh"; cd "$PROJECT_DIR"; eval "$1" ) || { release_rollback; die "$2"; }
  }

  release_step "mvn $flags $(mvn_versions_set) -DnewVersion=$version -DprocessAllModules=true -DgenerateBackupPoms=false" \
    "Could not set version $version."
  release_step "git commit -q -am 'Release $version' && git tag -a $tag -m 'Release $version'" "Could not commit the release."
  log_ok "Committed and tagged $tag"

  for line in "${deploys[@]}"; do
    name=${line%%|*}; args=${line#*|}
    log_step "[release] $name"
    release_step "$(stage_command "$flags" "$args")" "Stage $name failed; nothing was pushed."
    ran=$((ran + 1))
  done

  release_step "mvn $flags $(mvn_versions_set) -DnewVersion=$next -DprocessAllModules=true -DgenerateBackupPoms=false" \
    "Could not set version $next."
  release_step "git commit -q -am 'Prepare next development version $next'" "Could not commit $next."
  log_ok "Next development version $next"

  if (( push )); then
    ( cd "$PROJECT_DIR" && git push -q origin HEAD "$tag" ) \
      || die "Push failed. The release is committed and tagged locally; push with: git push origin HEAD $tag"
    log_ok "Pushed the branch and $tag"
  else
    log_info "Not pushed. Push with: git push origin HEAD $tag"
  fi
  log_ok "Released $version ($ran deploy stage(s))"
}
