# shellcheck shell=bash
# Load test of one environment with k6 (LOAD_TEST_ENVIRONMENT, by default the
# one before the last), right after the deployment to it, so a release that
# fails it goes no further (templates/scripts/load-test.sh).  The stage fails
# when the thresholds fail: by default more than 1% failed requests or a 95th
# percentile response time above 500 ms.  The project's own k6 script
# (LOAD_TEST_SCRIPT) replaces the generic one (templates/scripts/load-test.js).
# With the prometheus module the results go to Prometheus too.

# The environment before the last one, the only one when there is one.
default_environment() {
  local -a list
  read -r -a list <<< "$ENVIRONMENTS"
  if (( ${#list[@]} > 1 )); then
    printf '%s' "${list[${#list[@]} - 2]}"
  else
    printf '%s' "${list[0]}"
  fi
}

load_test_environment() { value LOAD_TEST_ENVIRONMENT "$(default_environment)"; }

module_secrets() {
  local found='' env
  ask LOAD_TEST_ENVIRONMENT "Environment the load test calls, right after it is deployed ($ENVIRONMENTS)" "$(default_environment)"
  env=$(load_test_environment)
  is_environment "$env" || die "LOAD_TEST_ENVIRONMENT '$env' is not one of the environments ($ENVIRONMENTS)"
  if [[ " $MODULES " != *" deploy/"* ]]; then
    ask LOAD_TEST_URL "URL of $env the load test calls, e.g. https://$env.example.com" ""
  else
    found=$(app_address "$env" host)
    [[ -n $found ]] || ask LOAD_TEST_URL "URL of $env the load test calls (a cluster of its own)" ""
  fi
  [[ -n $found || -n $(value LOAD_TEST_URL) ]] || die "The load test needs LOAD_TEST_URL, the address of $env."
  ask LOAD_TEST_SCRIPT "k6 script of the project (empty: call LOAD_TEST_PATHS)" ""
  ask LOAD_TEST_PATHS "Paths the generic script calls, comma separated" /actuator/health
  ask LOAD_TEST_VUS "Virtual users" 10
  ask LOAD_TEST_DURATION "Duration" 30s
  ask LOAD_TEST_P95_MS "Highest 95th percentile response time (ms)" 500
  ask LOAD_TEST_MAX_ERROR_RATE "Highest share of failed requests" 0.01
}

# URL of the tested environment as the pipeline reaches it.  A pipeline in Docker reaches
# the ports of this machine at the gateway of the tools' network: Concourse
# tasks cannot resolve host.docker.internal.
environment_url() {
  local address gateway env
  env=$(load_test_environment)
  if [[ -n $(value LOAD_TEST_URL) ]]; then
    value LOAD_TEST_URL
    return
  fi
  if [[ ${DEVOPS_RUNS_IN:-host} != docker ]]; then
    printf 'http://%s' "$(app_address "$env" host)"
    return
  fi
  address=$(app_address "$env" docker)
  if [[ $address == host.docker.internal:* ]]; then
    gateway=$(docker network inspect "$(compose_project)_default" \
      --format '{{range .IPAM.Config}}{{.Gateway}} {{end}}' 2> /dev/null | awk '{ print $1 }' || true)
    [[ -n $gateway ]] && address=$gateway:${address##*:}
  fi
  printf 'http://%s' "$address"
}

module_env() {
  local key
  pipeline_var LOAD_TEST_URL "$(environment_url)"
  pipeline_var LOAD_TEST_HEALTH_PATH "$(value DEPLOY_HEALTH_PATH /actuator/health)"
  for key in LOAD_TEST_SCRIPT LOAD_TEST_PATHS LOAD_TEST_VUS LOAD_TEST_DURATION LOAD_TEST_P95_MS LOAD_TEST_MAX_ERROR_RATE; do
    pipeline_var "$key" "$(value "$key")"
  done
  if [[ " $MODULES " == *" monitoring/prometheus "* ]]; then
    pipeline_var LOAD_TEST_PROMETHEUS_URL "$(pipeline_url prometheus 9090 "$(value PROMETHEUS_HOST_PORT 9090)")"
  else
    pipeline_var LOAD_TEST_PROMETHEUS_URL ''
  fi
}

module_stages() {
  local env
  env=$(load_test_environment)
  shell_stage 85 "$env" load-test "sh \"\$DEVOPS_SCRIPTS/load-test.sh\" $env"
}
