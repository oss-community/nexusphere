#!/bin/sh
# Releases an image to one environment the GitOps way: writes the chart and
# the environment's values to environments/<environment>/ of the GitOps
# branch, pushes, and waits until Argo CD has synced that commit and the
# application is healthy.  When it does not become healthy, the previous
# release is committed again.  Stage script of the gitops/argocd module;
# "devops.sh rollback" runs it too.
#
# The secrets of the environment (app-secrets.sh) are committed encrypted, as
# a SealedSecret, with the secrets/sealed-secrets module (SEALED_SECRETS=yes);
# otherwise they are put in the cluster directly, as the Secret <app>-env in
# the namespace <app>-<environment>.
#
#   deploy-gitops.sh <environment> [deploy [TAG] | rollback [TAG]]
#
# deploy uses the image of the current commit by default; rollback without a
# tag goes back to the release before the current one.
set -eu

environment=$1
action=${2:-deploy}
tag=${3:-}
case " ${ENVIRONMENTS:-staging production} " in
  *" $environment "*) ;;
  *) echo "deploy-gitops.sh: unknown environment '$environment'" >&2; exit 2 ;;
esac
key=$(printf '%s' "$environment" | tr '[:lower:]' '[:upper:]')
node_port=''
eval "node_port=\${KUBERNETES_${key}_NODE_PORT:-}"
# The last environment is released as a canary with GITOPS_CANARY=yes.
canary=false
last=${ENVIRONMENTS:-staging production}
[ "${GITOPS_CANARY:-no}" = yes ] && [ "$environment" = "${last##* }" ] && canary=true
case $action in
  deploy) [ -n "$tag" ] || tag=$(git rev-parse --short=12 HEAD) ;;
  rollback) ;;
  *) echo "deploy-gitops.sh: unknown action '$action'" >&2; exit 2 ;;
esac
scripts=$(cd "$(dirname "$0")" && pwd)
chart=${KUBERNETES_CHART:-$scripts/../helm/app}
[ -n "${KUBERNETES_CHART:-}" ] && chart=$(pwd)/$KUBERNETES_CHART
kubectl=$(sh "$scripts/tool.sh" kubectl)
app=$DEPLOY_NAME-$environment
dir=environments/$environment
timeout=${GITOPS_TIMEOUT:-600}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '%s' "$KUBECONFIG_B64" | base64 -d > "$work/kubeconfig"
chmod 600 "$work/kubeconfig"
export KUBECONFIG="$work/kubeconfig"

# The GitOps branch, cloned with the token so the pipeline may push.
url=$GITHUB_URL/$GITHUB_REPOSITORY.git
if [ -n "${GITHUB_TOKEN:-}" ]; then
  url=${url%%://*}://${GITHUB_USERNAME:-git}:$GITHUB_TOKEN@${url#*://}
fi
if ! git clone --quiet --branch "$GITOPS_BRANCH" "$url" "$work/gitops" 2> /dev/null; then
  git init --quiet "$work/gitops"
  git -C "$work/gitops" checkout --quiet --orphan "$GITOPS_BRANCH"
  git -C "$work/gitops" remote add origin "$url"
fi
cd "$work/gitops"
git() { command git -c user.name="${GITHUB_USERNAME:-mvn-devops}" -c user.email="${GITHUB_EMAIL:-mvn-devops@localhost}" "$@"; }

# tag_of <revision>: the image tag of the environment at a revision.
tag_of() { git show "$1:$dir/environment.yaml" 2> /dev/null | sed -n 's/^  "image": {"repository": .*, "tag": "\([^"]*\)"}.*/\1/p'; }

current=$(tag_of HEAD || true)
if [ "$action" = rollback ] && [ -z "$tag" ]; then
  for revision in $(git log --format=%H -- "$dir/environment.yaml"); do
    candidate=$(tag_of "$revision")
    if [ -n "$candidate" ] && [ "$candidate" != "$current" ]; then tag=$candidate; break; fi
  done
  [ -n "$tag" ] || { echo "No earlier release of $environment to go back to" >&2; exit 1; }
fi

json() { printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"; }

# secret_values: the environment's secrets for environment.yaml, sealed for
# the cluster's Sealed Secrets controller; without one they go to the cluster.
database='{"enabled": false}' db_host=''
if [ "${DATABASE_ENGINE:-}" = postgresql ]; then
  database="{\"enabled\": true, \"image\": $(json "$DATABASE_IMAGE"), \"name\": $(json "$DATABASE_NAME")}"
  db_host=$DEPLOY_NAME-db
fi
secrets=$(sh "$scripts/app-secrets.sh" "$environment" $db_host)
secret_values=''
if [ "$secrets" != '{}' ]; then
  jq=$(command -v jq 2> /dev/null || sh "$scripts/tool.sh" jq)
  if [ "${SEALED_SECRETS:-no}" = yes ]; then
    kubeseal=$(sh "$scripts/tool.sh" kubeseal)
    "$kubeseal" --fetch-cert --controller-namespace kube-system --controller-name sealed-secrets-controller > "$work/seal.pem"
    sealed='{}'
    for key in $(printf '%s' "$secrets" | "$jq" -r 'keys[]'); do
      value=$(printf '%s' "$secrets" | "$jq" -j --arg k "$key" '.[$k]' \
        | "$kubeseal" --raw --scope strict --namespace "$app" --name "$DEPLOY_NAME-env" --cert "$work/seal.pem")
      sealed=$(printf '%s' "$sealed" | "$jq" -c --arg k "$key" --arg v "$value" '. + {($k): $v}')
    done
    secret_values=",
  \"sealedSecretEnv\": $sealed"
  else
    echo "No Sealed Secrets in the cluster: the secrets of $environment go to the Secret $DEPLOY_NAME-env, not to $GITOPS_BRANCH"
    "$kubectl" create namespace "$app" --dry-run=client --output yaml | "$kubectl" apply --filename - > /dev/null
    printf '%s' "$secrets" | "$jq" --arg n "$DEPLOY_NAME-env" --arg ns "$app" \
      '{apiVersion: "v1", kind: "Secret", metadata: {name: $n, namespace: $ns}, type: "Opaque", stringData: .}' \
      | "$kubectl" apply --filename - > /dev/null
  fi
fi

# release <tag> <message>: commits the environment at <tag> and pushes it.
release() {
  rm -rf "$dir"
  mkdir -p "$dir"
  cp -R "$chart/." "$dir/"
  cat > "$dir/environment.yaml" <<EOF
{
  "image": {"repository": $(json "$IMAGE_DEPLOY_REPOSITORY"), "tag": $(json "$1")},
  "environment": $(json "$environment"),
  "replicas": ${KUBERNETES_REPLICAS:-2},
  "containerPort": ${DEPLOY_CONTAINER_PORT:-8080},
  "healthPath": $(json "${DEPLOY_HEALTH_PATH:-/actuator/health}"),
  "service": {"nodePort": $(json "$node_port")},
  "canary": {"enabled": $canary},
  "database": $database$secret_values
}
EOF
  git add --all
  if git diff --cached --quiet; then
    echo "$environment is already at $1 in $GITOPS_BRANCH"
    return 0
  fi
  git commit --quiet --message "$2"
  for attempt in 1 2 3; do
    git push --quiet origin "HEAD:$GITOPS_BRANCH" && return 0
    echo "Push to $GITOPS_BRANCH rejected (attempt $attempt); rebasing on the remote branch" >&2
    git pull --quiet --rebase origin "$GITOPS_BRANCH"
  done
  echo "Could not push to $GITOPS_BRANCH" >&2
  return 1
}

# wait_healthy: until Argo CD runs the pushed commit and the application is
# healthy (returns 0), degraded (1) or out of time (1).
wait_healthy() {
  revision=$(git rev-parse HEAD)
  "$kubectl" annotate application "$app" --namespace argocd argocd.argoproj.io/refresh=normal --overwrite > /dev/null
  waited=0 healthy=0 state=''
  while [ "$waited" -lt "$timeout" ]; do
    state=$("$kubectl" get application "$app" --namespace argocd \
      --output 'jsonpath={.status.sync.revision} {.status.sync.status} {.status.health.status}' 2> /dev/null || true)
    case $state in
      "$revision Synced Healthy")
        healthy=$((healthy + 1))
        [ "$healthy" -ge 2 ] && return 0 ;;
      "$revision "*" Degraded") echo "Argo CD reports $app as degraded" >&2; return 1 ;;
      *) healthy=0 ;;
    esac
    sleep 5
    waited=$((waited + 5))
  done
  echo "Argo CD did not report $app healthy at $revision within ${timeout}s (last: $state)" >&2
  return 1
}

echo "Releasing $IMAGE_DEPLOY_REPOSITORY:$tag to $environment through $GITOPS_BRANCH"
release "$tag" "Release $tag to $environment"
if wait_healthy; then
  echo "$environment runs $IMAGE_DEPLOY_REPOSITORY:$tag (Argo CD application $app)"
  exit 0
fi
if [ -n "$current" ] && [ "$current" != "$tag" ]; then
  echo "Releasing $current to $environment again" >&2
  release "$current" "Roll back $environment to $current" && wait_healthy || true
fi
exit 1
