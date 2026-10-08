# shellcheck shell=bash
# GitOps with Argo CD on the cluster of the kubernetes module.  The pipeline
# does not touch the cluster: it writes the chart and the release of each
# environment to the GitOps branch of the repository
# (environments/<environment>/) and waits until Argo CD has synced it and the
# application is healthy (templates/scripts/deploy-gitops.sh).  The last
# environment can be released as a canary with Argo Rollouts.
#
# configure installs Argo CD (and Argo Rollouts) in the cluster and creates
# one Argo CD Application per environment.

ARGOCD_VERSION=3.5.4
ROLLOUTS_VERSION=1.10.0

module_secrets() {
  ask GITOPS_BRANCH "Branch of the repository that holds the desired state" gitops
  ask GITOPS_CANARY "Release ${ENVIRONMENTS##* } as a canary with Argo Rollouts (yes or no)" yes
  server_external KUBERNETES || ask ARGOCD_HOST_PORT "Argo CD port on the Docker machine" 8443
}

# Repository URL as Argo CD and the pipeline reach it.
gitops_repo_url() { printf '%s/%s.git' "$(github_url)" "$(value GITHUB_REPOSITORY)"; }

applications() {
  local env app
  for env in $ENVIRONMENTS; do
    app="$(image_app_name)-$env"
    cat <<EOF
---
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: $app
  namespace: argocd
spec:
  project: default
  source:
    repoURL: $(gitops_repo_url)
    targetRevision: $(value GITOPS_BRANCH gitops)
    path: environments/$env
    helm:
      releaseName: $(image_app_name)
      valueFiles:
        - environment.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: $app
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
EOF
  done
}

repository_secret() {
  [[ -n $(value GITHUB_TOKEN) ]] || return 0
  cat <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: $(image_app_name)-gitops-repository
  namespace: argocd
  labels:
    argocd.argoproj.io/secret-type: repository
stringData:
  type: git
  url: $(gitops_repo_url)
  username: $(value GITHUB_USERNAME)
  password: $(value GITHUB_TOKEN)
EOF
}

module_configure() {
  local resource
  log_step "Argo CD $ARGOCD_VERSION"
  kubectl_host create namespace argocd --dry-run=client --output yaml | kubectl_host apply --filename - > /dev/null
  kubectl_host apply --namespace argocd --server-side --force-conflicts --output name \
    --filename "https://raw.githubusercontent.com/argoproj/argo-cd/v$ARGOCD_VERSION/manifests/install.yaml" > /dev/null \
    || die "Could not install Argo CD"
  if [[ $(value GITOPS_CANARY yes) == yes ]]; then
    log_step "Argo Rollouts $ROLLOUTS_VERSION"
    kubectl_host create namespace argo-rollouts --dry-run=client --output yaml | kubectl_host apply --filename - > /dev/null
    kubectl_host apply --namespace argo-rollouts --server-side --force-conflicts --output name \
      --filename "https://github.com/argoproj/argo-rollouts/releases/download/v$ROLLOUTS_VERSION/install.yaml" > /dev/null \
      || die "Could not install Argo Rollouts"
    kubectl_host rollout status deployment/argo-rollouts --namespace argo-rollouts --timeout 300s > /dev/null \
      || die "Argo Rollouts did not start"
  fi
  for resource in deployment/argocd-server deployment/argocd-repo-server statefulset/argocd-application-controller; do
    kubectl_host rollout status "$resource" --namespace argocd --timeout 600s > /dev/null || die "Argo CD did not start ($resource)"
  done
  log_ok "Argo CD runs"
  if ! server_external KUBERNETES; then
    kubectl_host patch service argocd-server --namespace argocd --type merge \
      --patch '{"spec":{"type":"NodePort","ports":[{"name":"http","port":80,"targetPort":8080,"nodePort":30480},{"name":"https","port":443,"targetPort":8080,"nodePort":30443}]}}' > /dev/null
  fi
  set_value ARGOCD_ADMIN_PASSWORD "$(kubectl_host get secret argocd-initial-admin-secret --namespace argocd \
    --output 'jsonpath={.data.password}' 2> /dev/null | base64 -d 2> /dev/null || true)" secret
  if [[ -n $(value GITHUB_TOKEN) ]]; then
    repository_secret | kubectl_host apply --filename - > /dev/null || die "Could not give Argo CD access to the repository"
  fi
  applications | kubectl_host apply --filename - > /dev/null || die "Could not create the Argo CD applications"
  log_ok "Applications $(image_app_name)-{${ENVIRONMENTS// /,}} follow $(gitops_repo_url) ($(value GITOPS_BRANCH gitops))"
}

module_env() {
  pipeline_var GITOPS_BRANCH "$(value GITOPS_BRANCH gitops)"
  pipeline_var GITOPS_CANARY "$(value GITOPS_CANARY yes)"
}

module_stages() {
  local env
  for env in $ENVIRONMENTS; do
    shell_stage 80 "$env" "deploy-$env" "sh \"\$DEVOPS_SCRIPTS/deploy-gitops.sh\" $env"
  done
}

# rollback [environment] [--to TAG]: a commit to the GitOps branch.
module_rollback() {
  local env tag
  rollback_args "$@"
  env=$ROLLBACK_ENV tag=$ROLLBACK_TAG
  log_step "Rollback of $env${tag:+ to $tag}"
  (
    source_pipeline_env
    export KUBECONFIG_B64
    KUBECONFIG_B64=$(kubeconfig host | base64 | tr -d '\n')
    cd "$PROJECT_DIR"
    sh "$DEVOPS_HOME/templates/scripts/deploy-gitops.sh" "$env" rollback "$tag"
  ) || die "Rollback of $env failed"
  log_ok "$env is rolled back"
}

module_urls() {
  if server_external KUBERNETES; then
    printf '  %-12s kubectl port-forward service/argocd-server --namespace argocd 8443:443\n' 'Argo CD'
  else
    printf '  %-12s https://%s:%s   (admin / devops.sh get ARGOCD_ADMIN_PASSWORD; self-signed certificate)\n' \
      'Argo CD' "$(devops_host)" "$(value ARGOCD_HOST_PORT 8443)"
  fi
}
