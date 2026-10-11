# Design

This page explains how mvn-devops is built: modules, the stages they
contribute, how orchestrators turn stages into a pipeline, and where values
and generated files are kept. Read it before you change the framework or
write a module.

## The idea

Each tool is a self-contained module with its own scripts. `devops.sh` asks
which tool you want from each category, then runs only the scripts of those
tools, in order, until everything is running, configured and wired into a
pipeline that is ready to run.

## Categories and modules

The tools are grouped in categories such as source control, build, pipeline
orchestrator, code quality and artifact repositories. A category is required,
allows exactly one module, at most one, or any number. The full list is in
[Modules and stages](modules.md).

A new tool only needs a new directory under `modules/<category>/<tool>/`; the
menu finds it on its own ([Writing a module](module-guide.md)).

## A module

```
modules/artifact/nexus/
  module.conf    name, description, modules it depends on
  compose.yml    the tool's containers
  module.sh      hooks
```

Every hook is one step of the life cycle, and every hook is optional:

| Hook | Job |
|---|---|
| `module_secrets` | ask for the values the tool needs (port, passwords, ...) |
| `module_prepare` | prepare files before the containers start |
| `module_configure` | finish setup once the tool is up: admin password, tokens, repositories |
| `module_env` | declare the variables the pipeline needs |
| `module_stages` | add Maven stages to the pipeline |
| `module_destroy` | undo what `configure` did outside Docker |
| `module_render`, `module_publish`, `module_run` | orchestrators only: build, install and run the pipeline |

Each hook runs in its own subshell, so modules cannot interfere with each
other.

## Stages come from the modules

Every module declares its stages with an order number and a phase (`ci`,
`cd`, or a deployment environment such as `staging`). For example sonarqube
says "stage 45 in phase ci:
`sonar-maven-plugin:sonar`". The orchestrator takes the sorted list and turns
it into its own format:

- **maven** runs every stage with `mvn` on your machine.
- **maven-container** writes a script that checks the commit out of GitHub
  and runs the stages with `mvn` in a container on the Docker machine.
- **jenkins** writes a Jenkinsfile and a `casc.yaml` that defines the admin
  user, the credentials and the job. No setup wizard and nothing to click.
- **concourse** writes a `pipeline.yml` with a `ci` job that runs on every
  push, a `cd` job you start by hand after `ci` passed, and a job per
  environment that needs approval.

So a new tool shows up in every orchestrator at once.

## The project's pom.xml needs no profiles

`profiles.xml` only existed in Maven 2. Profiles in `settings.xml` only accept
properties, repositories and activation, not plugins or
`distributionManagement`.

So stages call every plugin by its full coordinates and configure it with
`-D`, for example
`maven-deploy-plugin:3.2.0:deploy -DaltSnapshotDeploymentRepository=nexus-snapshots::<url>`.
Credentials come from the framework's own `templates/settings.xml`, passed
with `-gs` (global settings). A project adds no profile,
`distributionManagement` or settings to its pom. A project that has its own
profiles or settings file can still use them through `MAVEN_PROFILES` and
`MAVEN_SETTINGS`.

## How it fits together

```
devops.sh ── lib/  (menu, value store, env files, stages, compose)
   │
   └── modules/<category>/<tool>/
         module.conf    title, description, requirements
         module.sh      hooks: module_secrets, module_prepare, module_configure,
                        module_env, module_stages, module_urls
                        (+ module_render, module_publish, module_run for orchestrators)
         compose.yml    containers of the tool (optional)
```

The selected tools and every answer that is not a secret or personal are kept
in `<project>/devops.conf`, which you commit. Everything else is kept in
`<project>/.devops/` (it git-ignores itself):

```
.devops/
  values/<KEY>          one file per value, chmod 600
  env/pipeline.env      variables handed to the pipeline (compose env_file format)
  env/pipeline.sh       the same as bash exports
  env/compose.env       everything, for ${VAR} substitution in compose files
  generated/            Jenkinsfile, jenkins/casc.yaml, concourse/*.yml, pipeline.sh, maven-container/run.sh
  keys/                 generated SSH deploy key
```

Nothing is written to `~/.bashrc` or to system environment variables, and
secrets are never printed: `env --show` masks them and the orchestrators bind
them as masked credentials.

URLs handed to the pipeline depend on where it runs. A tool on an existing
server is always reached at its own URL. A tool in Docker is reached at
`DEVOPS_HOST:<port>` by the `maven` orchestrator and by an existing Jenkins or
Concourse server, and at its compose service name (`http://sonarqube:9000`) by
maven-container, and Jenkins or Concourse in Docker, which share its network. See
[Where the tools run](where-tools-run.md).

## Where the tools run

Every tool with a server (SonarQube, Nexus, Artifactory, Jenkins, Concourse)
has two modes:

- **In Docker:** devops.sh starts it, on this machine or on the machine
  `DOCKER_HOST` points to. It is reached through `DEVOPS_HOST`.
- **Existing server:** anywhere, with its own URL. `secrets` asks for
  `<TOOL>_SERVER_URL` and credentials, no container is created, and
  `configure` only checks the credentials and repositories.

Each tool is decided on its own, so any mix works. Details in
[Where the tools run](where-tools-run.md).

## Life cycle

```
init  →  secrets  →  up      →  configure  →  publish          →  run
menu     questions   Docker      tokens        install pipeline    run it
```

`mvn-devops/devops.sh setup` runs the first five steps in a row.

## Next

- [Modules and stages](modules.md): every module and the stages it adds.
- [Writing a module](module-guide.md): add a tool of your own.
- [Where the tools run](where-tools-run.md): Docker or an existing server, per tool.
