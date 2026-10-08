# shellcheck shell=bash
# PostgreSQL for the application, with its schema in Flyway migrations
# (src/main/resources/db/migration):
#
#   ci        the migrate stage empties a database in Docker (compose.yml) and
#             applies every migration from scratch, so a broken migration fails
#             the build, not a deployment;
#   deploy    each environment gets its own PostgreSQL next to the application:
#             a container on the machine (deploy/docker-host) or a StatefulSet
#             of the release (deploy/kubernetes, gitops/argocd).  The
#             application gets SPRING_DATASOURCE_URL, _USERNAME and _PASSWORD
#             (templates/scripts/app-secrets.sh) and applies the migrations
#             when it starts, as Spring Boot does with Flyway.
#
# Secrets in Vault with the same names win, e.g. for a managed database.

POSTGRES_VERSION=18

# Database and user name: the application name as an SQL identifier.
database_name() {
  local name
  name=$(value IMAGE_NAME "$PROJECT_NAME")
  name=${name##*/}
  printf '%s' "$name" | tr '[:upper:]-.' '[:lower:]__'
}

module_secrets() {
  local env
  ask DATABASE_HOST_PORT "Port of the ci database on the Docker machine" 5433
  ask DATABASE_NAME "Database and user name" "$(database_name)"
  ask FLYWAY_VERSION "Flyway version of the project (Spring Boot 4.1 manages 12.4.0)" 12.4.0
  ask FLYWAY_LOCATIONS "Flyway migrations" filesystem:src/main/resources/db/migration
  has_value DATABASE_CI_PASSWORD || set_value DATABASE_CI_PASSWORD "$(random_password)" secret
  for env in $ENVIRONMENTS; do
    has_value "DATABASE_$(upper "$env")_PASSWORD" \
      || set_value "DATABASE_$(upper "$env")_PASSWORD" "$(random_password)" secret
  done
}

module_configure() {
  local tries
  for (( tries = 0; tries < 60; tries++ )); do
    compose exec -T database pg_isready -q -h 127.0.0.1 -U "$(value DATABASE_NAME)" -d "$(value DATABASE_NAME)" 2> /dev/null && break
    sleep 2
  done
  compose exec -T database pg_isready -q -h 127.0.0.1 -U "$(value DATABASE_NAME)" -d "$(value DATABASE_NAME)" 2> /dev/null \
    || die "The ci database did not start. Check '$DEVOPS_CMD logs database'."
  log_ok "The ci database runs"
}

module_env() {
  local env host port
  host=$(devops_host)
  port=$(value DATABASE_HOST_PORT 5433)
  if [[ ${DEVOPS_RUNS_IN:-host} == docker ]]; then host=database port=5432; fi
  pipeline_var DATABASE_ENGINE postgresql
  pipeline_var DATABASE_IMAGE "postgres:$POSTGRES_VERSION"
  pipeline_var DATABASE_NAME "$(value DATABASE_NAME)"
  pipeline_var DATABASE_CI_URL "jdbc:postgresql://$host:$port/$(value DATABASE_NAME)"
  pipeline_secret DATABASE_CI_PASSWORD "$(value DATABASE_CI_PASSWORD)"
  for env in $ENVIRONMENTS; do
    pipeline_secret "DATABASE_$(upper "$env")_PASSWORD" "$(value "DATABASE_$(upper "$env")_PASSWORD")"
  done
}

flyway() { mvn_plugin FLYWAY_VERSION org.flywaydb:flyway-maven-plugin 12.4.0 "$1"; }

module_stages() {
  local module args
  module=$(value IMAGE_MODULE)
  args="$(flyway clean) $(flyway migrate) $(flyway validate)${module:+ -pl $module}"
  args+=" -Dflyway.url=\$DATABASE_CI_URL -Dflyway.user=\$DATABASE_NAME -Dflyway.password=\$DATABASE_CI_PASSWORD"
  args+=" -Dflyway.cleanDisabled=false -Dflyway.locations=$(value FLYWAY_LOCATIONS filesystem:src/main/resources/db/migration)"
  stage 35 ci migrate "$args"
}

module_urls() {
  printf '  %-12s %s   (user and database %s; devops.sh get DATABASE_CI_PASSWORD)\n' 'CI database' \
    "jdbc:postgresql://$(devops_host):$(value DATABASE_HOST_PORT 5433)/$(value DATABASE_NAME)" "$(value DATABASE_NAME)"
}
