# mvn-devops

A modular DevOps framework for Maven projects, written in plain Bash.

You pick the tools from a menu, mvn-devops starts them in Docker, configures
them (admin passwords, tokens, repositories) and turns the stages they
contribute into a pipeline for the orchestrator you chose: plain `mvn` on your
machine, `mvn` in a container on a Docker machine, Jenkins or Concourse.

```
$ mvn-devops/devops.sh init

Pipeline orchestrator (choose one)
  1) concourse        Concourse CI in Docker: a ci job on every push, a manual cd job
  2) jenkins          Jenkins in Docker, configured as code (no setup wizard)
  3) maven            Run the stages with mvn directly on this machine
  4) maven-container  Run the stages with mvn in a container on the Docker machine, from a fresh checkout of GitHub
  > [1]: 2

Code quality (choose any, comma separated, 0 for none)
  1) sonarqube        Static code analysis (SonarQube Community + PostgreSQL)
  > [0]: 1

Artifact repositories (choose any, comma separated, 0 for none)
  1) github-packages  Deploy to the Maven registry of the GitHub repository
  2) jfrog            JFrog Artifactory OSS + PostgreSQL
  3) nexus            Sonatype Nexus 3 for Maven releases and snapshots
  > [0]: 1,3
...
```

- **No changes to your pom.** Plugins are called by their coordinates and
  credentials come from the framework's own settings file.
- **Nothing to click.** Admin passwords, tokens, repositories, the Jenkins job
  and the Concourse pipeline are created for you.
- **Shared by the team.** The tools and answers are kept in a committed
  `devops.conf`; passwords and tokens stay on each machine.
- **Anywhere.** Each tool runs in Docker on your machine or a VM, or is an
  existing server with its own URL.

## Quick start

These steps set up a pipeline from the menu in the root of your Maven project,
with Docker running. On Windows, use `mvn-devops\devops.bat` instead of
`mvn-devops/devops.sh`. The full guides, including ready-made pipelines and a
teammate joining, are in [Getting started](docs/getting-started.md).

Step 1. Install the [prerequisites](docs/prerequisites.md) and create a GitHub token as described in [GitHub setup](docs/github-setup.md).

Step 2. Add mvn-devops to the project as described in [Installation](docs/installation.md#shipping-it-with-the-project-zip).

Step 3. Check the prerequisites:

```bash
mvn-devops/devops.sh doctor
```

Step 4. Choose the tools from the menu, answer the questions, and start, configure and publish everything:

```bash
mvn-devops/devops.sh setup
```

Step 5. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 6. Open the web consoles and see how to log in:

```bash
mvn-devops/devops.sh urls
```

Step 7. Commit the settings:

```bash
git add devops.conf mvn-devops && git commit -m "Add mvn-devops"
```

## Commands

| Command | |
|---|---|
| `init [--orchestrator X --with a,b \| --pipeline name]` | choose modules, or a ready-made pipeline |
| `pipelines` | list the ready-made pipelines |
| `setup [--pipeline name]` | init (if needed) + secrets + up + configure + publish |
| `secrets [--reconfigure]` | ask for values; `--reconfigure` asks again |
| `up`, `configure`, `publish` | single setup steps |
| `stages` | the ordered stages contributed by the selected modules |
| `render` | write pipeline files into `.devops/generated` |
| `run` | run the pipeline. maven, maven-container: `--dry-run`, `--from <stage>`, `--only <stage>`, `--phase ci\|cd`. concourse: `--phase ci\|cd`. Every orchestrator: `--phase <environment>` approves an environment |
| `rollback [environment] [--to TAG]` | put the previous image back, in the last environment by default |
| `release [--version X] [--next Y] [--dry-run] [--no-push]` | release the project ([docs/releasing.md](docs/releasing.md)) |
| `status`, `urls`, `logs [service]`, `compose ...` | operations |
| `down` | stop containers, keep data |
| `destroy` | remove containers and volumes, the deploy key and webhook on GitHub, optionally the stored values |
| `export-compose [dir]` | write one `docker-compose.yml` and its `.env` for the selected tools |
| `env [--show\|--windows]` | regenerate env files; `--show` masks secrets; `--windows` writes a `setx` script for IDEs |
| `get <KEY>` | print one stored value, e.g. `get SONAR_ADMIN_PASSWORD` |
| `modules`, `doctor [--fix]` | list modules, check prerequisites; `--fix` repairs CRLF line endings |
| `upgrade [--version X] [--check]` | replace the copy of mvn-devops inside the project with a release |

## How it works

mvn-devops needs Bash 4+ (Linux, macOS, or Git Bash on Windows), Docker with
the compose plugin, `curl`, `jq`, `git` and `ssh-keygen`, plus Java and Maven
when the pipeline runs on your machine. It lives inside the project, like the
Maven wrapper; `mvn-devops/devops.sh upgrade` later replaces it with the latest
release.

`setup` is these five steps; each can also be run on its own:

| Step | Command | What happens |
|---|---|---|
| 1 | `init` | Menu per category. Saves the choice in `devops.conf`. |
| 2 | `secrets` | Each selected module asks for the values it needs. Passwords default to random ones. |
| 3 | `up` | Modules prepare (config files, keys), then `docker compose up` with the selected tools. |
| 4 | `configure` | Modules finish their tool: replace default admin passwords, create tokens and repositories. |
| 5 | `publish` | The orchestrator renders the pipeline and installs it (Jenkins job, Concourse pipeline, local script). |

`devops.conf` is committed: a teammate who clones the project runs the same
`setup` and is only asked for their own passwords, tokens and user names.
Passwords, tokens and generated files go to `.devops/`, which keeps itself out
of git.

Instead of the menu you can start from a ready-made pipeline, a tested
combination of tools, and answer only for your GitHub user and token
([docs/pipelines.md](docs/pipelines.md)). Without questions, for scripts and
CI, `-y` accepts the defaults:

```bash
mvn-devops/devops.sh pipelines                      # list the ready-made pipelines
mvn-devops/devops.sh setup --pipeline maven-basic   # set one up
mvn-devops/devops.sh -y init --orchestrator jenkins --with sonarqube,nexus,github-pages
mvn-devops/devops.sh -y setup
```

[examples/hello-maven](examples/hello-maven) is a small project to try it on.

## Documentation

| | |
|---|---|
| [Documentation index](docs/README.md) | every page in reading order |
| [Getting started](docs/getting-started.md) | step-by-step guides |
| [Installation](docs/installation.md) | packages, the zip inside a project, upgrades, line endings |
| [Prerequisites](docs/prerequisites.md) | install commands for Windows, Linux and macOS |
| [GitHub setup](docs/github-setup.md) | tokens and their scopes, SSH key |
| [Ready-made pipelines](docs/pipelines.md) | tested combinations of tools, and your own |
| [Modules and stages](docs/modules.md) | every tool and the stages it adds |
| [Project requirements](docs/project-requirements.md) | what the Maven project needs (almost nothing) |
| [Orchestrators](docs/orchestrators.md) | maven, maven-container, Jenkins, Concourse, and the Docker images |
| [Where the tools run](docs/where-tools-run.md) | existing servers, a VM, the exported compose file |
| [Project site](docs/github-pages.md) | the Maven site on GitHub Pages |
| [Deployment](docs/deployment.md) | environments, container images, registries and deploying the application |
| [Releasing your project](docs/releasing.md) | `mvn-devops/devops.sh release` |
| [Troubleshooting](docs/troubleshooting.md) | common errors and their fixes |
| [IDE](docs/ide.md), [ngrok](docs/ngrok.md) | IntelliJ settings, exposing a local Jenkins to GitHub |
| [Design](docs/design.md) | how the framework is built |
| [Writing a module](docs/module-guide.md) | adding a tool |
| [Development](docs/development.md) | tests and releasing mvn-devops |
| [Changelog](CHANGELOG.md) | what changed in each version |

## License

Apache License 2.0
