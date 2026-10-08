# shellcheck shell=bash
# upgrade: replace this copy of mvn-devops with another release.
#
# Meant for the copy that ships inside a project (mvn-devops/ committed with
# it): the release tar.gz is downloaded from GitHub, checked against
# SHA256SUMS and unpacked over DEVOPS_HOME.  Package installs are upgraded by
# the package manager, git clones by git.

# Where releases are downloaded from; DEVOPS_RELEASES_URL serves tests and forks.
DEVOPS_REPOSITORY=oss-community/mvn-devops
releases_url() { printf '%s' "${DEVOPS_RELEASES_URL:-https://github.com/$DEVOPS_REPOSITORY/releases}"; }

latest_version() {
  curl -fsSL "https://api.github.com/repos/$DEVOPS_REPOSITORY/releases/latest" | jq -r '.tag_name // empty' | sed 's/^v//'
}

sha256() {
  if command -v sha256sum > /dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

cmd_upgrade() {
  local version='' check=0 current tmp name expected
  while (( $# )); do
    case $1 in
      --version) version=${2#v}; shift 2 ;;
      --check) check=1; shift ;;
      *) die "upgrade: unknown option $1 (use --version X or --check)" ;;
    esac
  done
  current=$(cat "$DEVOPS_HOME/VERSION")
  if [[ -z $version ]]; then
    version=$(latest_version) || true
    [[ -n $version ]] || die "Could not read the latest release from GitHub. Pass it with --version X."
  fi
  log_info "Installed: $current   Release: $version"
  if (( check )); then
    [[ $version == "$current" ]] && log_ok "Up to date"
    return 0
  fi
  if [[ $version == "$current" ]]; then
    log_ok "Already on $version"
    return 0
  fi
  if [[ -d "$DEVOPS_HOME/.git" ]]; then
    die "$DEVOPS_HOME is a git clone; update it with 'git -C $DEVOPS_HOME pull'."
  fi
  if [[ $DEVOPS_HOME == /usr/* || ! -w $DEVOPS_HOME ]]; then
    die "$DEVOPS_HOME was installed by a package; install the new package from $(releases_url)."
  fi

  name="mvn-devops-$version"
  tmp=$(mktemp -d)
  log_step "Downloading $name"
  curl -fsSL "$(releases_url)/download/v$version/$name.tar.gz" -o "$tmp/$name.tar.gz" \
    || { rm -rf "$tmp"; die "No release $version at $(releases_url)."; }
  curl -fsSL "$(releases_url)/download/v$version/SHA256SUMS" -o "$tmp/SHA256SUMS" \
    || { rm -rf "$tmp"; die "SHA256SUMS of $version is missing."; }
  expected=$(awk -v f="$name.tar.gz" '$2 == f || $2 == "*" f { print $1 }' "$tmp/SHA256SUMS")
  if [[ -z $expected || $(sha256 "$tmp/$name.tar.gz") != "$expected" ]]; then
    rm -rf "$tmp"
    die "Checksum of $name.tar.gz does not match SHA256SUMS; nothing was changed."
  fi
  tar -xzf "$tmp/$name.tar.gz" -C "$tmp"
  [[ -f "$tmp/$name/devops.sh" ]] || { rm -rf "$tmp"; die "$name.tar.gz has an unexpected layout."; }
  log_ok "Checksum verified"

  # The running devops.sh is part of what gets replaced, so the copy is done by
  # a script outside DEVOPS_HOME; bash closes this script's file on exec.
  cat > "$tmp/replace.sh" <<'EOF'
set -eu
home=$1 new=$2 tmp=$3 version=$4
find "$home" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
cp -R "$new/." "$home/"
rm -rf "$tmp"
printf 'Upgraded %s to %s.\n' "$home" "$version"
printf 'Review and commit the change, e.g. git add -A %s && git commit -m "Upgrade mvn-devops to %s"\n' "$home" "$version"
EOF
  log_step "Replacing $DEVOPS_HOME"
  exec bash "$tmp/replace.sh" "$DEVOPS_HOME" "$tmp/$name" "$tmp" "$version"
}
