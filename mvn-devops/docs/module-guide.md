# Writing a module

A module is a directory under `modules/<category>/<name>/`. The menu discovers
it automatically; no other file has to change. This page shows how to write
one, lists the hooks and helpers a module can use, and ends with a complete
example.

## Before you start

- A Maven project with mvn-devops in its `mvn-devops/` directory (see
  [Installation](installation.md)). The commands on this page run from the
  project root, so every path starts with `mvn-devops/`. On Windows, run
  `devops.sh` as `mvn-devops\devops.bat`.
- `shellcheck`, to check the module's script.

## Write a new module

Step 1. Create the module directory in its category:

```bash
mkdir -p mvn-devops/modules/artifact/reposilite
```

Step 2. Write `module.conf` in that directory with the keys listed under [module.conf](#moduleconf).

Step 3. Write `compose.yml` if the tool runs in Docker, as described under [compose.yml](#composeyml).

Step 4. Write `module.sh` with the hooks the tool needs, from the tables under [module.sh hooks](#modulesh-hooks) and [Helpers](#helpers).

Step 5. Check that the menu lists the module:

```bash
mvn-devops/devops.sh modules
```

Step 6. Select it and print the stages it adds:

```bash
mvn-devops/devops.sh init --orchestrator maven --with reposilite && mvn-devops/devops.sh stages
```

Step 7. Check the script with shellcheck:

```bash
shellcheck mvn-devops/modules/artifact/reposilite/module.sh
```

Step 8. Run the smoke test:

```bash
mvn-devops/tests/smoke.sh
```

## Add a category

Step 1. Create the category directory:

```bash
mkdir -p mvn-devops/modules/security
```

Step 2. Write `category.conf` in that directory with the keys listed under [category.conf](#categoryconf).

## Settings

### `module.conf`

Required in every module.

| Setting | Default | Meaning |
|---|---|---|
| `MODULE_TITLE` | empty | name of the tool, e.g. `"Nexus Repository"` |
| `MODULE_DESCRIPTION` | empty | one line shown in the menu |
| `MODULE_REQUIRES` | empty | other modules that must be selected too, e.g. `"scm/github"` |
| `MODULE_RUNS_IN` | empty | orchestrators only: `host` or `docker` |
| `MODULE_SERVER` | empty | tools with a server: prefix of `<PREFIX>_SERVER_URL`, e.g. `NEXUS` |

```bash
MODULE_TITLE="Nexus Repository"
MODULE_DESCRIPTION="Sonatype Nexus 3 for Maven releases and snapshots"
MODULE_REQUIRES="scm/github"
MODULE_SERVER=NEXUS
```

With `MODULE_SERVER`, the tool can also be an existing server: when
`<PREFIX>_SERVER_URL` has a value, the module's `compose.yml` is left out and
the hooks talk to that URL instead.

### `compose.yml`

Optional. A docker compose fragment. The fragments of all selected modules are
merged into one compose project (`devops-<project>`), so services reach each
other by service name. Use named volumes for data. Every stored value is
available for `${VAR}` substitution, plus `DEVOPS_HOME`, `DEVOPS_STATE`,
`PROJECT_DIR` and `PROJECT_NAME`. Use them for paths, since relative paths are
resolved against `.devops/`.

### `module.sh` hooks

Optional. Hook functions. Each hook runs in its own subshell with the library
loaded, `MODULE_ID` and `MODULE_DIR` set and the module's `module.conf`
sourced.

| Hook | Called by | Purpose |
|---|---|---|
| `module_secrets` | `secrets` | ask for values with `ask` / `ask_secret` |
| `module_prepare` | `up`, before containers start | render config files, create keys |
| `module_configure` | `configure`, after containers start | admin passwords, tokens, repositories |
| `module_env` | every command | export pipeline variables with `pipeline_var` / `pipeline_secret` |
| `module_stages` | `stages`, `render`, `run` | contribute stages with `stage` |
| `module_urls` | `urls`, end of `setup` | print the web console and how to log in |
| `module_rollback` | `rollback` | put the previous release back (deployment modules) |
| `module_destroy` | `destroy`, before containers are removed | undo what `configure` did outside Docker (deploy keys, webhooks) |
| `module_render` | orchestrators: `render`, `publish` | write the pipeline definition |
| `module_publish` | orchestrators: `publish` | install the pipeline |
| `module_run` | orchestrators: `run` | run it |

For orchestrators, `module_configure` runs during `publish` (after the tools are
configured) instead of during `configure`.

### Helpers

Functions and variables available in every hook.

| Helper | Meaning |
|---|---|
| `ask KEY "Question" [default]` | ask a value; stored in `.devops/values/KEY` and shared through `devops.conf` |
| `ask_local KEY "Question" [default]` | same, but never shared: user names, addresses of this machine |
| `ask_secret KEY "Question" [default]` | hidden input, masked everywhere |
| `value KEY [fallback]` | read a value |
| `require_value KEY` | read a value or fail |
| `set_value KEY VALUE [secret]` | store a computed value, e.g. a token |
| `random_password` | a random default password |
| `pipeline_var KEY VALUE` | hand a variable to the pipeline |
| `pipeline_secret KEY VALUE` | same, masked |
| `stage ORDER PHASE NAME "MAVEN ARGS"` | add a stage; `PHASE` is `ci`, `cd` or an environment (`prod`: the last); args may use `$VARS` |
| `shell_stage ORDER PHASE NAME "CMD"` | a POSIX shell command in the project root instead of `mvn` |
| `$ENVIRONMENTS` | the deployment environments, in order, e.g. `"staging production"` |
| `upper NAME` | `STAGING`: per-environment values are `<PREFIX>_<NAME>_<SETTING>` |
| `env_offset ENV` | `0` for the last environment, `1` for the one before, ... (default ports) |
| `env_gates` | the environments that wait for approval |
| `rollback_args "$@"` | parse `[environment] [--to TAG]` into `ROLLBACK_ENV`, `ROLLBACK_TAG` |
| `mvn_plugin KEY group:artifact VERSION GOAL` | full plugin coordinates, version overridable per project |
| `mvn_deploy_args SNAP_ID SNAP_URL REL_ID REL_URL` | package + attach + deploy without `distributionManagement` |
| `ask_server PREFIX "Title" [example]` | ask `<PREFIX>_SERVER_URL`; empty means Docker |
| `server_external PREFIX` | true when an existing server is used |
| `server_url PREFIX HOST_PORT [PATH]` | URL for configure hooks: the server, or `DEVOPS_HOST:port` |
| `server_pipeline_url PREFIX SERVICE PORT HOST_PORT [PATH]` | URL for the pipeline |
| `pipeline_url SERVICE PORT HOST_PORT [PATH]` | service name or `DEVOPS_HOST`, depending on the orchestrator |
| `host_url HOST_PORT [PATH]` | `DEVOPS_HOST` URL |
| `github_url` / `github_host` / `github_api` | github.com or GitHub Enterprise |
| `image_secrets` | image modules: name, module, builder, base image, port |
| `image_env PUSH_REPO DEPLOY_REPO [USER] [PASSWORD]` | `IMAGE_REPOSITORY` and the rest for the pipeline |
| `image_stages INSECURE AUTH` | the image stage (Jib, or the project's Dockerfile) |
| `compose ...` | docker compose of the project, e.g. `compose exec -T nexus ...` |
| `wait_http URL [timeout] [status regex]` | wait until a URL answers |
| `log_step` / `log_ok` / `log_warn` / `log_dim` / `die` / `confirm` | messages and questions |

### `category.conf`

| Setting | Default | Meaning |
|---|---|---|
| `CATEGORY_TITLE` | the directory name | title in the menu, e.g. `"Security scanning"` |
| `CATEGORY_ORDER` | `99` | position in the menu, e.g. `45` |
| `CATEGORY_MODE` | `multi` | `required`, `single` (exactly one), `optional` (at most one) or `multi` |

## Example: a new artifact repository

```
modules/artifact/reposilite/
  module.conf
  compose.yml
  module.sh
```

```bash
# module.sh
# module.conf has MODULE_SERVER=REPOSILITE
module_secrets() {
  ask_server REPOSILITE Reposilite https://repo.example.com
  server_external REPOSILITE || ask REPOSILITE_HOST_PORT "Reposilite port on the Docker machine" 8085
  ask_secret REPOSILITE_TOKEN "Reposilite deploy token" "$(random_password)"
}

module_env() {
  pipeline_var REPOSILITE_URL "$(server_pipeline_url REPOSILITE reposilite 8080 "$(value REPOSILITE_HOST_PORT 8085)")/snapshots"
  pipeline_secret REPOSILITE_TOKEN "$(value REPOSILITE_TOKEN)"
}

module_stages() {
  stage 73 cd deploy-reposilite "$(mvn_deploy_args reposilite '$REPOSILITE_URL' reposilite '$REPOSILITE_URL')"
}
```

A new category such as "Security scanning":

```bash
CATEGORY_TITLE="Security scanning"
CATEGORY_ORDER=45
CATEGORY_MODE=multi
```

## How it works

Call plugins by their coordinates and configure them with `-D` properties, so
projects need no profiles (see [What your Maven project needs](project-requirements.md)).
A deploy target also needs a `<server>` with its id in `templates/settings.xml`.

Stage arguments are embedded in single-quoted strings by the orchestrators, so
they may not contain `'`, `\`, `|` or `${`. Use `$VAR` instead of `${VAR}`.

A stage that needs more than one command calls a script from
`templates/scripts/`: `shell_stage 80 cd deploy "sh \"\$DEVOPS_SCRIPTS/deploy.sh\""`.
`DEVOPS_SCRIPTS` is that directory when the pipeline runs on this machine;
maven-container, Jenkins and Concourse get a copy in `.devops/scripts` before
the first stage. Scripts get every pipeline variable in the environment.

## Next

- [Modules and stages](modules.md): the modules that already exist.
- [Design](design.md): how modules, stages and orchestrators fit together.
- [Development](development.md): run the full test suite.
