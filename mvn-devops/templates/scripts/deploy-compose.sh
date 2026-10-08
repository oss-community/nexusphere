#!/bin/sh
# Deploys an image to one environment with Docker Compose over SSH, checks its
# health endpoint and puts the previous image back when the check fails.
# Stage script of the deploy/docker-host module; "devops.sh rollback" runs it
# too.
#
#   deploy-compose.sh <environment> [deploy [TAG] | rollback [TAG]]
#
# deploy uses the image of the current commit by default; rollback the image
# that ran before the current one.  The machine keeps one directory per
# application and environment ($HOME/mvn-devops/<name>-<environment>) with the
# compose file and the current and previous tags.  The secrets of the
# environment (app-secrets.sh) go to secrets.yml there, readable only by the
# deploy user.
set -eu

environment=$1
action=${2:-deploy}
tag=${3:-}
case " ${ENVIRONMENTS:-staging production} " in
  *" $environment "*) ;;
  *) echo "deploy-compose.sh: unknown environment '$environment'" >&2; exit 2 ;;
esac
key=$(printf '%s' "$environment" | tr '[:lower:]' '[:upper:]')
target='' ssh_port='' port=''
eval "target=\$DEPLOY_${key}_TARGET ssh_port=\$DEPLOY_${key}_SSH_PORT port=\$DEPLOY_${key}_PORT"
case $action in
  deploy) [ -n "$tag" ] || tag=$(git rev-parse --short=12 HEAD) ;;
  rollback) ;;
  *) echo "deploy-compose.sh: unknown action '$action'" >&2; exit 2 ;;
esac

if ! command -v ssh > /dev/null; then
  { apt-get update -qq && apt-get install -y -qq openssh-client; } > /dev/null 2>&1 \
    || apk add -q --no-cache openssh-client > /dev/null 2>&1 \
    || { echo "deploy-compose.sh: ssh is not installed" >&2; exit 1; }
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '%s' "$DEPLOY_SSH_KEY_B64" | base64 -d > "$work/key"
chmod 600 "$work/key"
printf '%s' "${DEPLOY_KNOWN_HOSTS_B64:-}" | base64 -d > "$work/known_hosts"

# The environment's secrets as a compose file, $ escaped from interpolation;
# with the database module also the database's password.
db_image=''
[ "${DATABASE_ENGINE:-}" = postgresql ] && db_image=$DATABASE_IMAGE
secrets=$(sh "$(dirname "$0")/app-secrets.sh" "$environment" ${db_image:+db})
if [ "$secrets" = '{}' ]; then
  secrets_yml='services: {app: {environment: {}}}'
else
  jq=$(command -v jq 2> /dev/null || sh "$(dirname "$0")/tool.sh" jq)
  secrets_yml=$(printf '%s' "$secrets" | "$jq" -r --arg db "$db_image" '
    def yaml: to_entries | map("      " + (.key | tojson) + ": " + (.value | gsub("\\$"; "$$") | tojson)) | join("\n");
    "services:\n  app:\n    environment:\n" + yaml
    + if $db == "" then "" else "\n  db:\n    environment:\n" + ({POSTGRES_PASSWORD: .SPRING_DATASOURCE_PASSWORD} | yaml) end')
fi

# Single-quoted for the remote shell.
q() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

{
  printf 'environment=%s action=%s tag=%s\n' "$(q "$environment")" "$(q "$action")" "$(q "$tag")"
  printf 'image=%s name=%s port=%s container_port=%s\n' "$(q "$IMAGE_DEPLOY_REPOSITORY")" \
    "$(q "$DEPLOY_NAME")" "$(q "$port")" "$(q "$DEPLOY_CONTAINER_PORT")"
  printf 'check=%s\n' "$(q "http://$DEPLOY_CHECK_HOST:$port$DEPLOY_HEALTH_PATH")"
  printf 'registry_user=%s registry_password=%s\n' "$(q "${IMAGE_REGISTRY_USERNAME:-}")" "$(q "${IMAGE_REGISTRY_PASSWORD:-}")"
  printf 'db_image=%s db_name=%s\n' "$(q "$db_image")" "$(q "${DATABASE_NAME:-}")"
  printf 'secrets_b64=%s\n' "$(printf '%s\n' "$secrets_yml" | base64 | tr -d '\n')"
  cat <<'REMOTE'
set -eu
dir=$HOME/mvn-devops/$name-$environment
mkdir -p "$dir"
cd "$dir"
current=$(cat current 2> /dev/null || true)
if [ "$action" = rollback ] && [ -z "$tag" ]; then
  tag=$(cat previous 2> /dev/null || true)
  [ -n "$tag" ] || { echo "No earlier deployment of $name in $environment to go back to" >&2; exit 1; }
fi

if [ -n "$registry_user" ]; then
  registry=${image%%/*}
  case $registry in *.*|*:*|localhost) ;; *) registry=docker.io ;; esac
  printf '%s' "$registry_password" | docker login --username "$registry_user" --password-stdin "$registry" > /dev/null
fi

cat > compose.yml <<EOF
name: $name-$environment
services:
  app:
    image: $image:\${TAG}
    restart: unless-stopped
    ports:
      - "$port:$container_port"
    environment:
      DEPLOY_ENVIRONMENT: $environment
    # Settings of this environment, kept on the machine.
    env_file:
      - path: app.env
        required: false
EOF
# The environment's database, its data in a volume of the machine.
if [ -n "$db_image" ]; then
  cat >> compose.yml <<EOF
    depends_on:
      db:
        condition: service_healthy
  db:
    image: $db_image
    restart: unless-stopped
    environment:
      POSTGRES_DB: $db_name
      POSTGRES_USER: $db_name
    healthcheck:
      test: ["CMD", "pg_isready", "-q", "-h", "127.0.0.1", "-U", "$db_name", "-d", "$db_name"]
      interval: 2s
      retries: 60
    volumes:
      - db-data:/var/lib/postgresql
volumes:
  db-data:
EOF
fi
(umask 077; printf '%s' "$secrets_b64" | base64 -d > secrets.yml)

start() {
  printf 'TAG=%s\nCOMPOSE_FILE=compose.yml:secrets.yml\n' "$1" > .env
  docker compose pull --quiet && docker compose up --detach --remove-orphans
}

healthy() {
  i=0
  while [ $i -lt 60 ]; do
    if curl -fsS -o /dev/null "$check" 2> /dev/null || wget -q -O /dev/null "$check" 2> /dev/null; then
      return 0
    fi
    i=$((i + 1))
    sleep 2
  done
  return 1
}

echo "Deploying $image:$tag to $environment"
if start "$tag" && healthy; then
  if [ "$current" != "$tag" ]; then
    if [ -n "$current" ]; then printf '%s\n' "$current" > previous; fi
    printf '%s\n' "$tag" > current
  fi
  echo "$environment runs $image:$tag, healthy at $check"
  exit 0
fi

echo "$image:$tag is not healthy at $check" >&2
docker compose logs --tail 50 app >&2 || true
if [ -n "$current" ] && [ "$current" != "$tag" ]; then
  echo "Putting back $image:$current" >&2
  if start "$current" && healthy; then
    echo "$environment runs $image:$current again" >&2
  fi
fi
exit 1
REMOTE
} > "$work/remote.sh"

ssh -i "$work/key" -p "$ssh_port" -o BatchMode=yes -o ConnectTimeout=30 \
  -o UserKnownHostsFile="$work/known_hosts" -o StrictHostKeyChecking=yes \
  -o HostKeyAlias="mvn-devops-$environment" "$target" sh -s < "$work/remote.sh"
