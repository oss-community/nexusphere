# Troubleshooting

This page lists common problems by the message or symptom you see, with the
steps that fix each one. On Windows, run `mvn-devops\devops.bat` instead of
`mvn-devops/devops.sh`.

## Find the cause

Step 1. Check the tools, Docker, the Java and Maven versions, your SSH key on GitHub and the line endings of the framework files:

```bash
mvn-devops/devops.sh doctor
```

Step 2. Show the containers of the tools:

```bash
mvn-devops/devops.sh status
```

Step 3. Show a tool's own log:

```bash
mvn-devops/devops.sh logs <service>
```

## `mvn-devops needs Bash 4 or newer`

Step 1. Install a newer Bash on macOS, whose own Bash is 3.2:

```bash
brew install bash
```

Step 2. Open a new terminal, so `#!/usr/bin/env bash` finds it.

## `$'\r': command not found`, `bad interpreter` or `invalid option name`

Step 1. List the files with Windows (CRLF) line endings and the command that fixes them:

```bash
mvn-devops/devops.sh doctor
```

Step 2. Run the command that `doctor` prints.

When the project committed mvn-devops before `mvn-devops/.gitattributes`
existed, follow [Repair the line endings of a checkout](installation.md#repair-the-line-endings-of-a-checkout) instead.

Step 3. Keep `mvn-devops/.gitattributes` committed so it does not happen again; [installation.md](installation.md#shipping-it-with-the-project-zip) shows how to repair a checkout.

## `Cannot connect to the Docker daemon`

Step 1. Start Docker Desktop, or the docker service on Linux.

Step 2. With `DOCKER_HOST` set, check that the other machine is reachable:

```bash
docker info
```

## A question is not asked any more, or a teammate's answer is used

Step 1. Answer again, or edit `devops.conf` and commit it:

```bash
mvn-devops/devops.sh secrets --reconfigure
```

## `port is already allocated` during `up`

Step 1. Change the port, e.g. `NEXUS_HOST_PORT`, here or in `devops.conf`:

```bash
mvn-devops/devops.sh secrets --reconfigure
```

Step 2. Start the tools again:

```bash
mvn-devops/devops.sh up
```

## SonarQube log shows `high disk watermark` or `no_shard_available_action_exception`

Step 1. Free disk space, for example:

```bash
docker system prune
```

Step 2. Stop the tools:

```bash
mvn-devops/devops.sh down
```

Step 3. Start them again:

```bash
mvn-devops/devops.sh up
```

## A tool keeps restarting after its version changed

Step 1. Remove the containers and volumes:

```bash
mvn-devops/devops.sh destroy
```

Step 2. Start the tools fresh:

```bash
mvn-devops/devops.sh up && mvn-devops/devops.sh configure
```

## Jenkins in Docker on another machine does not see its configuration

Step 1. Clone mvn-devops and the project on the Docker machine and run devops.sh there for Jenkins ([where-tools-run.md](where-tools-run.md)).

## Deploy to Nexus fails with 400 or 403

Step 1. Set `NEXUS_ACCEPT_EULA` to `yes`:

```bash
mvn-devops/devops.sh secrets --reconfigure
```

Step 2. Apply it:

```bash
mvn-devops/devops.sh configure
```

## Deploy to Artifactory fails with 404

Step 1. Open Artifactory and choose Quick Setup > Maven.

Step 2. Enter the prefix from `JFROG_ARTIFACTORY_REPOSITORY_PREFIX` ([modules.md](modules.md)).

## Checkstyle fails

Step 1. Point `MAVEN_CHECKSTYLE_CONFIG` at your own rules file, or set `MAVEN_CHECKSTYLE` to `no` ([project-requirements.md](project-requirements.md)).

## Jenkins does not build on push

Step 1. Run the pipeline once, so the trigger records the repository:

```bash
mvn-devops/devops.sh run
```

Step 2. For `webhook`, make Jenkins reachable from GitHub ([ngrok.md](ngrok.md)).

## `publish-site` is refused

Step 1. Create the `site` branch and allow your SSH key (with the `maven` orchestrator) or the deploy key to push ([github-pages.md](github-pages.md)).

## Start over

Step 1. Remove the containers, volumes, deploy key and webhook; it asks before deleting `.devops/`:

```bash
mvn-devops/devops.sh destroy
```

Step 2. Set up everything again; `devops.conf` keeps the team's answers:

```bash
mvn-devops/devops.sh setup
```

## How it works

**Answers.** Answers in the committed `devops.conf` win over your own. A new
answer from `secrets --reconfigure` is written back to `devops.conf` for
everyone.

**Line endings.** The scripts get Windows (CRLF) line endings usually from git
on Windows with `core.autocrlf=true`.

**Ports.** `port is already allocated` means another program, or another
project's tool, uses the port.

**SonarQube.** Its search engine does not start when the disk holding Docker
is more than 90% full.

**Tool versions.** The tools' data is kept in Docker volumes, and a newer
version may not read an older one's data.

**Jenkins in Docker on another machine.** Jenkins mounts files from
`.devops/`, which only exist on the machine running devops.sh.

**Nexus.** Nexus Community Edition accepts uploads only after its EULA is
accepted, even when the password is right.

**Artifactory.** Artifactory OSS does not let devops.sh create repositories.

**Checkstyle.** The default rules are `google_checks.xml`.

**Jenkins triggers.** With `poll` or `webhook`, the trigger starts working
after the first build, which records the repository.

**Site publishing.** The `site` branch must exist and the key must be allowed
to push.

## Next

- [Getting started](getting-started.md): set up a project step by step.
- [Where the tools run](where-tools-run.md): tools on another machine or an existing server.
- [Orchestrators](orchestrators.md): how each orchestrator runs the pipeline.
