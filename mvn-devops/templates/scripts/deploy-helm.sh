#!/bin/sh
# Deploys an image to one environment of a Kubernetes cluster with Helm.
# Helm waits until the rolling update's new pods are ready and rolls the
# release back when they are not.  Stage script of the deploy/kubernetes
# module; "devops.sh rollback" runs it too.
#
#   deploy-helm.sh <environment> [deploy [TAG] | rollback [TAG]]
#
# deploy uses the image of the current commit by default; rollback without a
# tag goes back to the previous Helm revision.  The release is $DEPLOY_NAME in
# the namespace $DEPLOY_NAME-<environment>.  The secrets of the environment
# (app-secrets.sh) go to the Secret $DEPLOY_NAME-env there.
set -eu

environment=$1
action=${2:-deploy}
tag=${3:-}
case " ${ENVIRONMENTS:-staging production} " in
  *" $environment "*) ;;
  *) echo "deploy-helm.sh: unknown environment '$environment'" >&2; exit 2 ;;
esac
key=$(printf '%s' "$environment" | tr '[:lower:]' '[:upper:]')
node_port=''
eval "node_port=\${KUBERNETES_${key}_NODE_PORT:-}"
scripts=$(dirname "$0")
helm=$(sh "$scripts/tool.sh" helm)
release=$DEPLOY_NAME
namespace=$DEPLOY_NAME-$environment
chart=${KUBERNETES_CHART:-$scripts/../helm/app}
timeout=${KUBERNETES_TIMEOUT:-5m}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '%s' "$KUBECONFIG_B64" | base64 -d > "$work/kubeconfig"
chmod 600 "$work/kubeconfig"
export KUBECONFIG="$work/kubeconfig"

if [ "$action" = rollback ] && [ -z "$tag" ]; then
  # The previous revision with the secrets it had.
  "$helm" rollback "$release" --namespace "$namespace" --wait --timeout "$timeout"
  "$helm" history "$release" --namespace "$namespace" --max 3
  exit 0
fi
case $action in
  deploy | rollback) [ -n "$tag" ] || tag=$(git rev-parse --short=12 HEAD) ;;
  *) echo "deploy-helm.sh: unknown action '$action'" >&2; exit 2 ;;
esac

# Values as JSON, which is YAML too.
json() { printf '"%s"' "$(printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"; }
registry_auth=''
if [ -n "${IMAGE_REGISTRY_PASSWORD:-}" ]; then
  registry=${IMAGE_DEPLOY_REPOSITORY%%/*}
  case $registry in *.* | *:* | localhost) ;; *) registry=https://index.docker.io/v1/ ;; esac
  auth=$(printf '%s:%s' "$IMAGE_REGISTRY_USERNAME" "$IMAGE_REGISTRY_PASSWORD" | base64 | tr -d '\n')
  registry_auth=$(printf '{"auths":{"%s":{"auth":"%s"}}}' "$registry" "$auth")
fi
database='{"enabled": false}' db_host=''
if [ "${DATABASE_ENGINE:-}" = postgresql ]; then
  database="{\"enabled\": true, \"image\": $(json "$DATABASE_IMAGE"), \"name\": $(json "$DATABASE_NAME")}"
  db_host=$release-db
fi
secrets=$(sh "$scripts/app-secrets.sh" "$environment" $db_host)
cat > "$work/values.yaml" <<EOF
{
  "image": {"repository": $(json "$IMAGE_DEPLOY_REPOSITORY"), "tag": $(json "$tag")},
  "registryAuth": $(json "$registry_auth"),
  "environment": $(json "$environment"),
  "replicas": ${KUBERNETES_REPLICAS:-2},
  "containerPort": ${DEPLOY_CONTAINER_PORT:-8080},
  "healthPath": $(json "${DEPLOY_HEALTH_PATH:-/actuator/health}"),
  "service": {"nodePort": $(json "$node_port")},
  "database": $database,
  "secretEnv": $secrets
}
EOF

echo "Deploying $IMAGE_DEPLOY_REPOSITORY:$tag to $environment (namespace $namespace)"
"$helm" upgrade "$release" "$chart" --install --namespace "$namespace" --create-namespace \
  --values "$work/values.yaml" --rollback-on-failure --wait --timeout "$timeout"
"$helm" history "$release" --namespace "$namespace" --max 3
echo "$environment runs $IMAGE_DEPLOY_REPOSITORY:$tag"
