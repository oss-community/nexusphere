# Writing a module

A module is a directory under `modules/<category>/<name>/`. The menu discovers
it automatically; no other file has to change.

## Files

### `module.conf` (required)

```bash
MODULE_TITLE="Nexus Repository"
MODULE_DESCRIPTION="Sonatype Nexus 3 for Maven releases and snapshots"   # shown in the menu
MODULE_REQUIRES="scm/github"     # other modules that must be selected too (optional)
MODULE_RUNS_IN=docker            # orchestrators only: host or docker
MODULE_SERVER=NEXUS              # tools with a server: prefix of <PREFIX>_SERVER_URL (optional)
```

With `MODULE_SERVER`, the tool can also be an existing server: when
`<PREFIX>_SERVER_URL` has a value, the module's `compose.yml` is left out and
the hooks talk to that URL instead.

### `compose.yml` (optional)

A docker compose fragment. The fragments of all selected modules are merged
into one compose project (`devops-<project>`), so services reach each other by
service name. Use named volumes for data. Every stored value is available for
`${VAR}` substitution, plus `DEVOPS_HOME`, `DEVOPS_STATE`, `PROJECT_DIR` and
`PROJECT_NAME`. Use them for paths, since relative paths are resolved against
`.devops/`.

### `module.sh` (optional)

Hook functions. Each hook runs in its own subshell with the library loaded,
`MODULE_ID` and `MODULE_DIR` set and the module's `module.conf` sourced.

| Hook | Called by | Purpose |
|---|---|---|
| `module_secrets` | `secrets` | ask for values with `ask` / `ask_secret` |
| `module_prepare` | `up`, before containers start | render config files, create keys |
| `module_configure` | `configure`, after containers start | admin passwords, tokens, repositories |
| `module_env` | every command | export pipeline variables with `pipeline_var` / `pipeline_secret` |
| `module_stages` | `stages`, `render`, `run` | contribute stages with `stage` |
| `module_urls` | `urls`, end of `setup` | print the web console and how to log in |
| `module_destroy` | `destroy`, before containers are removed | undo what `configure` did outside Docker (deploy keys, webhooks) |
| `module_render` | orchestrators: `render`, `publish` | write the pipeline definition |
| `module_publish` | orchestrators: `publish` | install the pipeline |
| `module_run` | orchestrators: `run` | run it |

For orchestrators, `module_configure` runs during `publish` (after the tools are
configured) instead of during `configure`.

## Helpers

```bash
ask KEY "Question" [default]          # stored in .devops/values/KEY; kept on later runs
ask_secret KEY "Question" [default]   # hidden input, masked everywhere
value KEY [fallback]                  # read a value
require_value KEY                     # read or fail
set_value KEY VALUE [secret]          # store a computed value, e.g. a token
random_password                       # a random default password

pipeline_var KEY VALUE                # hand a variable to the pipeline
pipeline_secret KEY VALUE             # same, masked

stage ORDER PHASE NAME "MAVEN ARGS"   # PHASE is ci or cd; args may use $VARS
shell_stage ORDER PHASE NAME "CMD"    # a POSIX shell command in the project root instead of mvn
mvn_plugin KEY group:artifact VERSION GOAL   # full plugin coordinates, version overridable per project
mvn_deploy_args SNAP_ID SNAP_URL REL_ID REL_URL   # package + attach + deploy without distributionManagement
ask_server PREFIX "Title" [example]   # ask <PREFIX>_SERVER_URL; empty means Docker
server_external PREFIX                # true when an existing server is used
server_url PREFIX HOST_PORT [PATH]    # URL for configure hooks: the server, or DEVOPS_HOST:port
server_pipeline_url PREFIX SERVICE PORT HOST_PORT [PATH]   # URL for the pipeline
pipeline_url SERVICE PORT HOST_PORT [PATH]   # service name or DEVOPS_HOST, depending on the orchestrator
host_url HOST_PORT [PATH]             # DEVOPS_HOST URL
github_url / github_host / github_api # github.com or GitHub Enterprise

compose ...                           # docker compose of the project, e.g. compose exec -T nexus ...
wait_http URL [timeout] [status regex]
log_step / log_ok / log_warn / log_dim / die / confirm
```

Call plugins by their coordinates and configure them with `-D` properties, so
projects need no profiles (see [project-requirements.md](project-requirements.md)).
A deploy target also needs a `<server>` with its id in `templates/settings.xml`.

Stage arguments are embedded in single-quoted strings by the orchestrators, so
they may not contain `'`, `\`, `|` or `${`. Use `$VAR` instead of `${VAR}`.

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

A new category is a directory with a `category.conf`:

```bash
CATEGORY_TITLE="Security scanning"
CATEGORY_ORDER=45          # position in the menu
CATEGORY_MODE=multi        # required | single | multi
```

Run `tests/smoke.sh` and `shellcheck` after adding a module.
