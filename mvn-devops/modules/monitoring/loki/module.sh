# shellcheck shell=bash
# Logs of the deployed application in Loki, searchable in Grafana (the
# dashboard "Application logs", logs.json) with the labels application,
# environment, container and source.  Grafana Alloy
# collects them: from the containers of the simulated machine of docker-host,
# which run on this Docker daemon, and from the pods of k3s.  Machines and
# clusters of their own need an Alloy (or another agent) there that pushes to
# Loki (LOKI_PUSH_URL in devops.sh urls).

module_secrets() {
  ask LOKI_HOST_PORT "Loki port on the Docker machine" 3100
}

alloy_config() {
  local app pattern env paths=''
  app=$(image_app_name)
  pattern="${app//./\\\\.}-(${ENVIRONMENTS// /|})"
  for env in $ENVIRONMENTS; do
    paths+="    {\"__path__\" = \"/var/log/pods/$app-${env}_*/*/*.log\", \"application\" = \"$app\", \"environment\" = \"$env\", \"source\" = \"kubernetes\"},"$'\n'
  done
  cat <<EOF
// Written by mvn-devops (monitoring/loki); changes are overwritten.

// Containers of the application's compose projects (<app>-<environment>).
discovery.docker "containers" {
  host = "unix:///var/run/docker.sock"
}

discovery.relabel "app" {
  targets = discovery.docker.containers.targets
  rule {
    source_labels = ["__meta_docker_container_label_com_docker_compose_project"]
    regex         = "$pattern"
    action        = "keep"
  }
  rule {
    source_labels = ["__meta_docker_container_label_com_docker_compose_project"]
    regex         = "$pattern"
    target_label  = "environment"
  }
  rule {
    source_labels = ["__meta_docker_container_label_com_docker_compose_service"]
    target_label  = "container"
  }
  rule {
    target_label = "application"
    replacement  = "$app"
  }
  rule {
    target_label = "source"
    replacement  = "docker"
  }
}

loki.source.docker "app" {
  host       = "unix:///var/run/docker.sock"
  targets    = discovery.relabel.app.output
  forward_to = [loki.write.local.receiver]
}

// Pods of the namespaces <app>-<environment> in k3s.
local.file_match "pods" {
  path_targets = [
$paths  ]
}

loki.source.file "pods" {
  targets    = local.file_match.pods.targets
  forward_to = [loki.process.pods.receiver]
}

loki.process "pods" {
  stage.cri {}
  stage.regex {
    source     = "filename"
    expression = "/var/log/pods/[^/]+/(?P<container>[^/]+)/"
  }
  stage.labels {
    values = {container = ""}
  }
  stage.label_drop {
    values = ["filename"]
  }
  forward_to = [loki.write.local.receiver]
}

loki.write "local" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"
  }
}
EOF
}

module_prepare() {
  local dir="$DEVOPS_STATE/monitoring"
  mkdir -p "$dir/alloy" "$dir/grafana/dashboards"
  alloy_config > "$dir/alloy/config.alloy"
  # The environment filter offers the project's environments.
  sed "s/\"query\": \"staging,production\"/\"query\": \"${ENVIRONMENTS// /,}\"/" \
    "$(module_dir monitoring/loki)/logs.json" > "$dir/grafana/dashboards/logs.json"
}

module_configure() {
  wait_http "$(host_url "$(value LOKI_HOST_PORT 3100)" /ready)" 180 '^200$' \
    || die "Loki did not start. Check '$DEVOPS_CMD logs loki'."
  log_ok "Loki runs; Alloy sends it the application's logs"
}

module_urls() {
  printf '  %-12s %s   (search in Grafana: Explore > Loki, or the dashboard "Application logs")\n' \
    Loki "$(host_url "$(value LOKI_HOST_PORT 3100)" /loki/api/v1/push)"
}
