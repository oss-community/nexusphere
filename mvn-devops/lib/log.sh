# shellcheck shell=bash
# Logging and small UI helpers.

if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  C_RESET=$'\e[0m'; C_BOLD=$'\e[1m'; C_DIM=$'\e[2m'
  C_RED=$'\e[31m'; C_GREEN=$'\e[32m'; C_YELLOW=$'\e[33m'; C_BLUE=$'\e[34m'
else
  C_RESET=''; C_BOLD=''; C_DIM=''; C_RED=''; C_GREEN=''; C_YELLOW=''; C_BLUE=''
fi

log_step()  { printf '\n%s==> %s%s\n' "$C_BOLD$C_BLUE" "$*" "$C_RESET"; }
log_info()  { printf '%s\n' "$*"; }
log_ok()    { printf '%s✔ %s%s\n' "$C_GREEN" "$*" "$C_RESET"; }
log_warn()  { printf '%s! %s%s\n' "$C_YELLOW" "$*" "$C_RESET" >&2; }
log_error() { printf '%s✘ %s%s\n' "$C_RED" "$*" "$C_RESET" >&2; }
log_dim()   { printf '%s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }
die()       { log_error "$*"; exit 1; }

# confirm "Question" [default y|n]
confirm() {
  local question=$1 default=${2:-n} answer hint='[y/N]'
  [[ $default == y ]] && hint='[Y/n]'
  if [[ ${DEVOPS_DEFAULTS:-0} == 1 ]]; then
    [[ $default == y ]]
    return
  fi
  read -r -p "$question $hint " answer || true
  answer=${answer:-$default}
  [[ $answer =~ ^[Yy] ]]
}
