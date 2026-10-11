# Where the tools run

No tool has to run on `localhost`. Each tool with a server is either started
in Docker by devops.sh, on this machine or another one, or is an existing
server with its own URL. This page shows both choices. On Windows, run
`mvn-devops\devops.bat` instead of `mvn-devops/devops.sh`.

| | How | Asked in `secrets` |
|---|---|---|
| started in Docker by devops.sh | on this machine, or on the machine `DOCKER_HOST` points to | `DEVOPS_HOST`: address of the Docker machine |
| an existing server | anywhere, with its own URL; nothing is started for it | `<TOOL>_SERVER_URL`, plus the credentials |

Each tool is decided on its own, so any mix works: SonarQube on
`https://sonar.acme.com`, Nexus in Docker on a VM, Jenkins on
`https://jenkins.acme.com`, and the repository on GitHub Enterprise.

## Use an existing server

Step 1. Answer the questions again:

```bash
mvn-devops/devops.sh secrets --reconfigure
```

Step 2. Enter the tool's server URL and credentials (see [Settings](#settings)); leave a `*_SERVER_URL` empty to run that tool in Docker.

Step 3. Check the credentials and the repositories:

```bash
mvn-devops/devops.sh configure
```

Step 4. Install the pipeline, when Jenkins or Concourse is an existing server:

```bash
mvn-devops/devops.sh publish
```

## Run the tools on another machine

Step 1. Point Docker at the other machine, or use a docker context:

```bash
export DOCKER_HOST=ssh://user@build-vm
```

Step 2. Check that the Docker daemon on the other machine is reachable:

```bash
mvn-devops/devops.sh doctor
```

Step 3. Set up the tools; the containers start on the other machine:

```bash
mvn-devops/devops.sh setup
```

Step 4. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

You can also clone mvn-devops and the project on the other machine and run
`mvn-devops/devops.sh` there. For Jenkins in Docker you must do this; see
[How it works](#how-it-works).

## Write the compose file of your tools

Step 1. Write the selected tools as one plain compose project:

```bash
mvn-devops/devops.sh export-compose ./docker
```

Step 2. Start them without devops.sh:

```bash
cd docker && docker compose up -d
```

## Settings

| Tool | Existing server | What devops.sh asks for it |
|---|---|---|
| GitHub | `GITHUB_URL`, e.g. `https://github.acme.com` (detected from `origin`) | token as for github.com |
| SonarQube | `SONAR_SERVER_URL` | an analysis token (My Account > Security) |
| Nexus | `NEXUS_SERVER_URL` | a user that may deploy, its password, repository names |
| Artifactory | `JFROG_SERVER_URL`, ending in `/artifactory` | a user, its password, API key or identity token, repository names |
| GitHub Packages | `GITHUB_PACKAGES_REGISTRY` (`https://maven.<host>` on Enterprise) | |
| Jenkins | `JENKINS_SERVER_URL` | a user and its API token |
| Concourse | `CONCOURSE_SERVER_URL` | team, user and password |

Examples of `export-compose`:

```bash
mvn-devops/devops.sh export-compose            # .devops/compose/docker-compose.yml + .env
mvn-devops/devops.sh export-compose ./docker   # or into a folder of the project
```

## How it works

**Existing servers.** For an existing server, `configure` only checks the
credentials and the repositories; it does not change passwords or create
anything there. For Jenkins, `publish` creates or updates the job and its
credentials through the REST API (credential ids get the prefix `<project>-`,
because the server is shared). The agents need git, ssh, Java 21 and Maven
3.9, and the server the plugins workflow-aggregator, git, credentials-binding,
plain-credentials and timestamper. For Concourse, `publish` downloads `fly`
from the server and sets the pipeline in your team.

**Another machine.** With `DOCKER_HOST` set, the containers start on the other
machine and, with the maven orchestrator, `mvn` runs on your machine and talks
to `build-vm:<port>`. With the `maven-container` orchestrator `mvn` runs on the
other machine as well, in a container next to the tools, and your machine
needs only Docker's client and devops.sh
([getting-started.md](getting-started.md#guide-3-maven-in-a-container-on-another-machine)).

`secrets` takes the default for `DEVOPS_HOST` from `DOCKER_HOST` or the docker
context. The ports must be reachable from where the pipeline runs (firewall,
security group). Jenkins in Docker mounts its configuration from `.devops/`,
which a remote Docker daemon cannot see, so run devops.sh on the other machine
for it.

When an existing Jenkins or Concourse server runs the pipeline and some tools
run in Docker, set `DEVOPS_HOST` to an address that server can reach;
`publish` warns when it is `localhost`.

**The compose file.** `up` also writes the selected tools as one plain compose
project, so you can read it, keep it and run it without devops.sh.
`docker-compose.yml` merges the compose fragments of the selected modules;
paths are written out and every other `${VAR}` (ports, passwords) is read from
the `.env` next to it. `.env` holds the passwords, so it is `chmod 600` and,
outside `.devops`, added to a `.gitignore` in that folder. The compose project
name is `devops-<project>`, the same one devops.sh uses, so both manage the
same containers. Tools on an existing server are not in the file.

## Next

- [Orchestrators](orchestrators.md): how each orchestrator runs the pipeline.
- [ngrok](ngrok.md): let GitHub reach a tool on a machine without a public address.
- [Troubleshooting](troubleshooting.md): what to do when a tool does not start.
