# shellcheck shell=bash
# The Kubernetes cluster of the deploy/kubernetes module, shared with the
# modules that build on it (gitops/argocd).

k3s_dir() { printf '%s/k3s' "$DEVOPS_STATE"; }

# kubeconfig <host|pipeline>: the cluster's kubeconfig as this machine or the
# pipeline reaches the API server.  Empty before the cluster is up.
kubeconfig() {
  local server file
  if server_external KUBERNETES; then
    file=$(value KUBERNETES_KUBECONFIG "$HOME/.kube/config")
    [[ -f $file ]] && cat "$file"
    return 0
  fi
  file="$(k3s_dir)/kubeconfig.yaml"
  [[ -f $file ]] || return 0
  if [[ $1 == pipeline && ${DEVOPS_RUNS_IN:-host} == docker ]]; then
    server=https://k3s:6443
  else
    server="https://$(devops_host):$(value KUBERNETES_API_HOST_PORT 6443)"
  fi
  sed "s#server: https://[^ ]*#server: $server#" "$file"
}

# kubectl_host <args>: kubectl (downloaded on first use) against the cluster
# as this machine reaches it.
kubectl_host() {
  local kubectl kc status=0
  kubectl=$(sh "$DEVOPS_HOME/templates/scripts/tool.sh" kubectl) || die "Could not download kubectl"
  kc=$(mktemp)
  kubeconfig host > "$kc"
  KUBECONFIG=$kc "$kubectl" "$@" || status=$?
  rm -f "$kc"
  return "$status"
}
