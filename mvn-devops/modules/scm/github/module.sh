# shellcheck shell=bash
# GitHub or GitHub Enterprise Server: repository coordinates and the
# credentials every other module uses.

# detect_origin <host|repository>: parsed from the origin remote.
detect_origin() {
  local url host path
  url=$(git -C "$PROJECT_DIR" remote get-url origin 2>/dev/null || true)
  url=${url%.git}
  case $url in
    ssh://*|https://*|http://*) url=${url#*://}; url=${url#*@}; host=${url%%/*}; path=${url#*/}; host=${host%%:*} ;;
    *@*:*) url=${url#*@}; host=${url%%:*}; path=${url#*:} ;;
    *) return 0 ;;
  esac
  if [[ $1 == host ]]; then printf '%s' "$host"; else printf '%s' "$path"; fi
}

detect_branch() {
  git -C "$PROJECT_DIR" symbolic-ref --short HEAD 2>/dev/null || printf 'main'
}

module_secrets() {
  local host
  host=$(detect_origin host)
  ask GITHUB_URL "GitHub URL (GitHub Enterprise: your server)" "https://${host:-github.com}"
  set_value GITHUB_URL "$(github_url | sed 's:/*$::')"
  ask GITHUB_REPOSITORY "GitHub repository (owner/name)" "$(detect_origin repository)"
  ask GIT_BRANCH "Branch the pipeline builds" "$(detect_branch)"
  ask GITHUB_USERNAME "GitHub username" "$(git config --global user.name 2>/dev/null || true)"
  ask GITHUB_EMAIL "GitHub email" "$(git config --global user.email 2>/dev/null || true)"
  log_dim "  Token scopes: repo (+ write:packages, read:packages without a packages token). See docs/github-setup.md"
  ask_secret GITHUB_TOKEN "GitHub personal access token"
  ask_secret GITHUB_PACKAGE_TOKEN "GitHub Packages token (empty: use the token above)"
}

module_env() {
  local token package_token
  token=$(value GITHUB_TOKEN)
  package_token=$(value GITHUB_PACKAGE_TOKEN)
  package_token=${package_token:-$token}
  pipeline_var GITHUB_URL "$(github_url)"
  pipeline_var GITHUB_HOST "$(github_host)"
  pipeline_var GITHUB_REPOSITORY "$(value GITHUB_REPOSITORY)"
  pipeline_var GIT_BRANCH "$(value GIT_BRANCH main)"
  pipeline_var GITHUB_USERNAME "$(value GITHUB_USERNAME)"
  pipeline_var GITHUB_EMAIL "$(value GITHUB_EMAIL)"
  pipeline_secret GITHUB_TOKEN "$token"
  pipeline_secret GITHUB_PACKAGE_TOKEN "$package_token"
}

module_urls() {
  printf '  %-12s %s/%s\n' GitHub "$(github_url)" "$(value GITHUB_REPOSITORY)"
}
