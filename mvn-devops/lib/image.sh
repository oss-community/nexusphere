# shellcheck shell=bash
# Container images, shared by the modules of the "image" category.
#
# A registry module asks image_secrets, exports image_env with the repository
# the pipeline pushes to, and adds image_stages.  The image is built with Jib
# by default: no Docker daemon and no Dockerfile are needed, so it works in
# every orchestrator.  A project's own Dockerfile is used instead with
# IMAGE_BUILDER=dockerfile, which needs Docker where the pipeline runs (the
# maven orchestrator).
#
# Every image gets two tags: the commit (git rev-parse --short=12 HEAD), which
# never changes and is what deployments use, and "latest" for people.

mvn_jib() { mvn_plugin MVN_JIB_VERSION com.google.cloud.tools:jib-maven-plugin 3.5.2 build; }

# The tag deployments use; evaluated where the pipeline runs.
IMAGE_TAG_EXPR='$(git rev-parse --short=12 HEAD)'

image_secrets() {
  local name builder=jib
  name=$(printf '%s' "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]')
  ask IMAGE_NAME "Image name" "$name"
  ask IMAGE_MODULE "Maven module of the application (empty: the root project)" ""
  [[ -f "$PROJECT_DIR/Dockerfile" && ${DEVOPS_RUNS_IN:-host} == host ]] && builder=dockerfile
  ask IMAGE_BUILDER "Build the image with jib or with the project's dockerfile" "$builder"
  if [[ $(value IMAGE_BUILDER jib) == jib ]]; then
    ask IMAGE_BASE "Base image" "eclipse-temurin:$(value JAVA_VERSION 21)-jre"
  fi
  ask IMAGE_PORT "Port the application listens on in the container" 8080
}

# image_env <push repository> <deploy repository> [user] [password] [insecure]
#   push repository    where the pipeline pushes, as the pipeline reaches the registry
#   deploy repository  the same image as the machines that run it pull it
#   insecure           1 for a registry without TLS
# The image stage writes the digest of the pushed image to IMAGE_DIGEST_FILE.
image_env() {
  local module
  module=$(value IMAGE_MODULE)
  pipeline_var IMAGE_REPOSITORY "$1"
  pipeline_var IMAGE_DEPLOY_REPOSITORY "$2"
  pipeline_var IMAGE_REGISTRY_USERNAME "${3:-}"
  pipeline_secret IMAGE_REGISTRY_PASSWORD "${4:-}"
  pipeline_var IMAGE_REGISTRY_INSECURE "${5:-0}"
  pipeline_var IMAGE_DIGEST_FILE "${module:+$module/}target/jib-image.digest"
}

# Name of the deployed application (compose project, Helm release, namespace
# prefix): the image name without registry namespace separators.
image_app_name() {
  local name
  name=$(value IMAGE_NAME "$PROJECT_NAME")
  printf '%s' "${name//\//-}" | tr '[:upper:]' '[:lower:]'
}

# Modules that work on the image (deployment, security) need an image module.
require_image_module() {
  [[ " $MODULES " == *" image/"* ]] \
    || die "$(module_field "$MODULE_ID" MODULE_TITLE) needs an image; select a module of 'Container image' with '$DEVOPS_CMD init'."
}

# image_stages <insecure> <auth>: insecure=1 for a registry without TLS,
# auth=1 when the registry needs IMAGE_REGISTRY_USERNAME and _PASSWORD.
image_stages() {
  local insecure=${1:-0} auth=${2:-1} args module
  module=$(value IMAGE_MODULE)
  if [[ $(value IMAGE_BUILDER jib) == dockerfile ]]; then
    [[ ${DEVOPS_RUNS_IN:-host} == host ]] \
      || die "IMAGE_BUILDER=dockerfile needs Docker where the pipeline runs; use jib with $ORCHESTRATOR."
    shell_stage 75 cd image "bash \"\$DEVOPS_SCRIPTS/image-dockerfile.sh\"${module:+ $module}"
    return
  fi
  args="package -DskipTests=true"
  [[ -n $module ]] && args+=" -pl $module"
  args+=" $(mvn_jib) -Djib.from.image=$(value IMAGE_BASE eclipse-temurin:21-jre)"
  args+=" -Djib.to.image=\$IMAGE_REPOSITORY:$IMAGE_TAG_EXPR -Djib.to.tags=latest"
  (( auth )) && args+=" -Djib.to.auth.username=\$IMAGE_REGISTRY_USERNAME -Djib.to.auth.password=\$IMAGE_REGISTRY_PASSWORD"
  args+=" -Djib.container.ports=$(value IMAGE_PORT 8080) -Djib.container.creationTime=USE_CURRENT_TIMESTAMP"
  (( insecure )) && args+=" -Djib.allowInsecureRegistries=true -DsendCredentialsOverHttp=true"
  stage 75 cd image "$args"
}

# deploy_server_of <environment>: ssh:// URL of the machine of an
# environment with the deploy/docker-host module, empty for the simulated
# one: DEPLOY_SERVER_URL for the first environment, DEPLOY_<NAME>_SERVER_URL
# (the same machine by default) for the others.
deploy_server_of() {
  local first
  first=$(environments)
  first=${first%% *}
  if [[ $1 == "$first" ]]; then
    value DEPLOY_SERVER_URL
  else
    value "DEPLOY_$(upper "$1")_SERVER_URL" "$(value DEPLOY_SERVER_URL)"
  fi
}

# app_address <environment> <host|docker>: host:port of the deployed
# application in an environment, as this machine (host) or a container next
# to the tools (docker) reaches it; empty when it is out of reach (a cluster
# of its own).
app_address() {
  local env=$1 url local_host
  local_host=$(devops_host)
  [[ $2 == docker ]] && local_host=host.docker.internal
  if [[ " $MODULES " == *" deploy/docker-host "* ]]; then
    if server_external DEPLOY; then
      url=$(deploy_server_of "$env")
      url=${url#ssh://}; url=${url%%/*}; url=${url#*@}
      printf '%s:%s' "${url%:*}" "$(value "DEPLOY_$(upper "$env")_PORT")"
    else
      printf '%s:%s' "$local_host" "$(value "DEPLOY_$(upper "$env")_PORT")"
    fi
  elif [[ " $MODULES " == *" deploy/kubernetes "* ]] && ! server_external KUBERNETES; then
    printf '%s:%s' "$local_host" "$(value "KUBERNETES_$(upper "$env")_PORT")"
  fi
}
