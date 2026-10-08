# shellcheck shell=bash
# Sealed Secrets in the cluster of the kubernetes module: kubeseal encrypts
# secrets with the controller's public key, so they can live in git, and only
# the controller in the cluster can turn them back into Secrets.  With
# gitops/argocd the release of each environment in the GitOps branch carries
# its secrets (from secrets/vault) as a SealedSecret
# (templates/scripts/deploy-gitops.sh).
#
# configure installs the controller in kube-system.

SEALED_SECRETS_VERSION=0.40.0

module_secrets() {
  [[ " $MODULES " == *" secrets/vault "* ]] \
    || log_dim "  Without Vault the pipeline has no secrets to seal; kubeseal still works for your own."
}

module_configure() {
  log_step "Sealed Secrets $SEALED_SECRETS_VERSION"
  kubectl_host apply --server-side --force-conflicts --output name \
    --filename "https://github.com/bitnami-labs/sealed-secrets/releases/download/v$SEALED_SECRETS_VERSION/controller.yaml" > /dev/null \
    || die "Could not install Sealed Secrets"
  kubectl_host rollout status deployment/sealed-secrets-controller --namespace kube-system --timeout 300s > /dev/null \
    || die "The Sealed Secrets controller did not start"
  log_ok "The Sealed Secrets controller runs in kube-system"
}

module_env() {
  pipeline_var SEALED_SECRETS yes
}

module_urls() {
  printf '  %-12s kubeseal --controller-namespace kube-system < secret.yaml > sealed-secret.yaml\n' 'Seal'
}
