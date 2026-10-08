# Troubleshooting

Start with `devops.sh doctor`: it checks the tools, Docker, the Java and Maven
versions, your SSH key on GitHub and the line endings of the framework files.
`devops.sh logs <service>` shows a tool's own log, and `devops.sh status` its
containers.

## Running devops.sh

**`mvn-devops needs Bash 4 or newer`** on macOS, whose own Bash is 3.2.
Install a newer one with `brew install bash` and open a new terminal;
`#!/usr/bin/env bash` then finds it.

**`$'\r': command not found`, `bad interpreter` or `invalid option name`**:
the scripts got Windows (CRLF) line endings, usually from git on Windows with
`core.autocrlf=true`. `doctor` lists the files and prints the command that
fixes them. Keep `mvn-devops/.gitattributes` committed so it does not happen
again; [installation.md](installation.md#shipping-it-with-the-project-zip)
shows how to repair a checkout.

**`Cannot connect to the Docker daemon`**: start Docker Desktop, or the docker
service on Linux. With `DOCKER_HOST` set, check that the other machine is
reachable (`docker info`).

**A question is not asked any more, or a teammate's answer is used**: answers
in the committed `devops.conf` win over your own. Run
`devops.sh secrets --reconfigure` to answer again (the new answer is written
back to `devops.conf` for everyone), or edit `devops.conf` and commit it.

## Starting the tools

**`port is already allocated`** during `up`: another program (or another
project's tool) uses the port. Change it, e.g. `NEXUS_HOST_PORT`, with
`devops.sh secrets --reconfigure` or in `devops.conf`, then run `up` again.

**SonarQube never comes up and its log shows `high disk watermark` or
`no_shard_available_action_exception`**: SonarQube's search engine does not
start when the disk holding Docker is more than 90% full. Free space, for
example with `docker system prune`, then `devops.sh down` and `up`.

**A tool keeps restarting after its version changed**: the tools' data is
kept in Docker volumes, and a newer version may not read an older one's data.
`devops.sh destroy` removes the containers and volumes, then `up` and
`configure` start them fresh.

**Jenkins in Docker on another machine** does not see its configuration:
it mounts files from `.devops/`, which only exist on the machine running
devops.sh. Run devops.sh on the Docker machine for Jenkins.

## Running the pipeline

**Deploy to Nexus fails with 400 or 403** although the password is right:
Nexus Community Edition accepts uploads only after its EULA is accepted. Set
`NEXUS_ACCEPT_EULA` to `yes` with `secrets --reconfigure` and run `configure`.

**Deploy to Artifactory fails with 404**: Artifactory OSS does not let
devops.sh create repositories. Open Artifactory, choose Quick Setup > Maven
and enter the prefix from `JFROG_ARTIFACTORY_REPOSITORY_PREFIX`
([modules.md](modules.md)).

**Checkstyle fails**: the default rules are `google_checks.xml`. Point
`MAVEN_CHECKSTYLE_CONFIG` at your own file, or set `MAVEN_CHECKSTYLE` to `no`
([project-requirements.md](project-requirements.md)).

**Jenkins does not build on push**: with `poll` or `webhook`, the trigger
starts working after the first build, which records the repository. Run
`devops.sh run` once. A webhook also needs Jenkins reachable from GitHub
([ngrok.md](ngrok.md)).

**`publish-site` is refused**: the `site` branch must exist and your SSH key
(with the `maven` orchestrator) or the deploy key must be allowed to push
([github-pages.md](github-pages.md)).

## Starting over

```bash
devops.sh destroy     # containers, volumes, deploy key and webhook; asks before deleting .devops/
devops.sh setup       # everything again; devops.conf keeps the team's answers
```
