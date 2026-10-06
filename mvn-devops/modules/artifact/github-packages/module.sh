# shellcheck shell=bash
# GitHub Packages needs no container: artifacts go to the Maven registry of the
# repository, https://maven.pkg.github.com/<owner>/<repo> on github.com.

module_secrets() {
  local default=https://maven.pkg.github.com
  [[ $(github_host) == github.com ]] || default="https://maven.$(github_host)"
  ask GITHUB_PACKAGES_REGISTRY "GitHub Packages Maven registry" "$default"
}

module_env() {
  pipeline_var GITHUB_PACKAGES_URL "$(value GITHUB_PACKAGES_REGISTRY https://maven.pkg.github.com)/$(value GITHUB_REPOSITORY)"
}

module_stages() {
  stage 71 cd deploy-github "$(mvn_deploy_args github '$GITHUB_PACKAGES_URL' github '$GITHUB_PACKAGES_URL')"
}

module_urls() {
  printf '  %-12s %s/%s/packages\n' Packages "$(github_url)" "$(value GITHUB_REPOSITORY)"
}
