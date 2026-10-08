# shellcheck shell=bash
# Metrics of the deployed application: Prometheus scrapes the actuator's
# Prometheus endpoint (/actuator/prometheus, from micrometer-registry-prometheus
# in the project) of each environment, labelled with the environment's name,
# and alerts when an environment is down or fails requests.
# Grafana shows them on the dashboard "Application" (grafana/application.json).
#
# The environments are found from the deploy module: the machines of
# docker-host, or the ports of k3s.  The deployments expose the endpoint
# (MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE, templates/scripts/app-secrets.sh).

module_secrets() {
  ask PROMETHEUS_HOST_PORT "Prometheus port on the Docker machine" 9090
  ask GRAFANA_HOST_PORT "Grafana port on the Docker machine" 3000
  ask METRICS_PATH "Path of the application's Prometheus metrics" /actuator/prometheus
  has_value GRAFANA_ADMIN_PASSWORD || set_value GRAFANA_ADMIN_PASSWORD "$(random_password)" secret
}

prometheus_config() {
  local env target
  printf '# Written by mvn-devops (monitoring/prometheus); changes are overwritten.\n'
  printf 'global:\n  scrape_interval: 15s\n'
  printf 'rule_files:\n  - /etc/prometheus/rules.yml\n'
  printf 'scrape_configs:\n'
  printf '  - job_name: prometheus\n    static_configs:\n      - targets: [localhost:9090]\n'
  printf '  - job_name: app\n    metrics_path: %s\n    static_configs:\n' "$(value METRICS_PATH /actuator/prometheus)"
  for env in $ENVIRONMENTS; do
    target=$(app_address "$env" docker)
    [[ -n $target ]] || continue
    printf '      - targets: ["%s"]\n        labels: {application: "%s", environment: "%s"}\n' \
      "$target" "$(image_app_name)" "$env"
  done
}

prometheus_rules() {
  cat <<'RULES'
# Written by mvn-devops (monitoring/prometheus); changes are overwritten.
groups:
  - name: application
    rules:
      - alert: ApplicationDown
        expr: up{job="app"} == 0
        for: 2m
        labels: {severity: critical}
        annotations:
          summary: "{{ $labels.application }} in {{ $labels.environment }} does not answer"
      - alert: HighErrorRate
        expr: >-
          sum by (environment) (rate(http_server_requests_seconds_count{job="app", status=~"5.."}[5m]))
          / sum by (environment) (rate(http_server_requests_seconds_count{job="app"}[5m])) > 0.05
        for: 5m
        labels: {severity: warning}
        annotations:
          summary: "More than 5% of the requests in {{ $labels.environment }} fail"
RULES
}

grafana_datasources() {
  printf '# Written by mvn-devops (monitoring/prometheus); changes are overwritten.\n'
  printf 'apiVersion: 1\ndatasources:\n'
  printf '  - {name: Prometheus, uid: prometheus, type: prometheus, url: "http://prometheus:9090", isDefault: true}\n'
  if [[ " $MODULES " == *" monitoring/loki "* ]]; then
    printf '  - {name: Loki, uid: loki, type: loki, url: "http://loki:3100"}\n'
  fi
}

module_prepare() {
  local dir="$DEVOPS_STATE/monitoring"
  mkdir -p "$dir/prometheus" "$dir/grafana/provisioning/datasources" "$dir/grafana/provisioning/dashboards" "$dir/grafana/dashboards"
  prometheus_config > "$dir/prometheus/prometheus.yml"
  prometheus_rules > "$dir/prometheus/rules.yml"
  grafana_datasources > "$dir/grafana/provisioning/datasources/datasources.yml"
  printf 'apiVersion: 1\nproviders:\n  - {name: mvn-devops, type: file, options: {path: /var/lib/grafana/dashboards}}\n' \
    > "$dir/grafana/provisioning/dashboards/dashboards.yml"
  cp "$(module_dir monitoring/prometheus)/grafana/application.json" "$dir/grafana/dashboards/"
}

module_configure() {
  wait_http "$(host_url "$(value PROMETHEUS_HOST_PORT 9090)" /-/ready)" 120 '^200$' \
    || die "Prometheus did not start. Check '$DEVOPS_CMD logs prometheus'."
  # Reload, for targets that changed since it started.
  curl -s -o /dev/null -X POST "$(host_url "$(value PROMETHEUS_HOST_PORT 9090)" /-/reload)" || true
  wait_http "$(host_url "$(value GRAFANA_HOST_PORT 3000)" /api/health)" 180 '^200$' \
    || die "Grafana did not start. Check '$DEVOPS_CMD logs grafana'."
  log_ok "Prometheus and Grafana run"
  [[ -n $(app_address "${ENVIRONMENTS%% *}" docker) ]] \
    || log_warn "Prometheus cannot reach the application in this cluster; scrape it with a Prometheus there."
}

module_env() {
  pipeline_var METRICS_EXPOSURE "health,info,prometheus"
}

module_urls() {
  printf '  %-12s %s   (alerts: %s)\n' Prometheus "$(host_url "$(value PROMETHEUS_HOST_PORT 9090)")" \
    "$(host_url "$(value PROMETHEUS_HOST_PORT 9090)" /alerts)"
  printf '  %-12s %s   (admin / devops.sh get GRAFANA_ADMIN_PASSWORD)\n' Grafana "$(host_url "$(value GRAFANA_HOST_PORT 3000)")"
}
