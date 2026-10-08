# Where the tools run

No tool has to run on `localhost`. Every tool with a server can be one of:

| | How | Asked in `secrets` |
|---|---|---|
| started in Docker by devops.sh | on this machine, or on the machine `DOCKER_HOST` points to | `DEVOPS_HOST`: address of the Docker machine |
| an existing server | anywhere, with its own URL; nothing is started for it | `<TOOL>_SERVER_URL`, plus the credentials |

Each tool is decided on its own, so any mix works: SonarQube on
`https://sonar.acme.com`, Nexus in Docker on a VM, Jenkins on
`https://jenkins.acme.com`, and the repository on GitHub Enterprise.

| Tool | Existing server | What devops.sh asks for it |
|---|---|---|
| GitHub | `GITHUB_URL`, e.g. `https://github.acme.com` (detected from `origin`) | token as for github.com |
| SonarQube | `SONAR_SERVER_URL` | an analysis token (My Account > Security) |
| Nexus | `NEXUS_SERVER_URL` | a user that may deploy, its password, repository names |
| Artifactory | `JFROG_SERVER_URL`, ending in `/artifactory` | a user, its password, API key or identity token, repository names |
| GitHub Packages | `GITHUB_PACKAGES_REGISTRY` (`https://maven.<host>` on Enterprise) | |
| Jenkins | `JENKINS_SERVER_URL` | a user and its API token |
| Concourse | `CONCOURSE_SERVER_URL` | team, user and password |

For an existing server, `configure` only checks the credentials and the
repositories; it does not change passwords or create anything there. For
Jenkins, `publish` creates or updates the job and its credentials through the
REST API (credential ids get the prefix `<project>-`, because the server is
shared). The agents need git, ssh, Java 21 and Maven 3.9, and the server the
plugins workflow-aggregator, git, credentials-binding, plain-credentials and
timestamper. For Concourse, `publish` downloads `fly` from the server and sets
the pipeline in your team.

Leave a `*_SERVER_URL` empty to run that tool in Docker. Change your answers
later with `devops.sh secrets --reconfigure`.

## Tools in Docker on another machine

Clone mvn-devops and the project on the VM and run `devops.sh` there, or point
Docker at the VM and run everything from your machine:

```bash
export DOCKER_HOST=ssh://user@build-vm      # or a docker context
../mvn-devops/devops.sh doctor              # docker daemon reachable on build-vm
../mvn-devops/devops.sh setup               # containers start on the VM
../mvn-devops/devops.sh run                 # mvn runs here and talks to build-vm:<port>
```

With the `maven-container` orchestrator mvn runs on the VM as well, in a
container next to the tools, and your machine needs only Docker's client and
`devops.sh` ([getting-started.md](getting-started.md#guide-3-maven-in-a-container-on-another-machine)).

`secrets` takes the default for `DEVOPS_HOST` from `DOCKER_HOST` or the docker
context. The ports must be reachable from where the pipeline runs (firewall,
security group). Jenkins in Docker mounts its configuration from `.devops/`,
which a remote Docker daemon cannot see, so run `devops.sh` on the VM for it.

When an existing Jenkins or Concourse server runs the pipeline and some tools
run in Docker, set `DEVOPS_HOST` to an address that server can reach;
`publish` warns when it is `localhost`.

## The compose file of your tools

`up` also writes the selected tools as one plain compose project, so you can
read it, keep it and run it without devops.sh:

```bash
../mvn-devops/devops.sh export-compose            # .devops/compose/docker-compose.yml + .env
../mvn-devops/devops.sh export-compose ./docker   # or into a folder of the project
cd docker && docker compose up -d
```

`docker-compose.yml` merges the compose fragments of the selected modules;
paths are written out and every other `${VAR}` (ports, passwords) is read from
the `.env` next to it. `.env` holds the passwords, so it is `chmod 600` and,
outside `.devops`, added to a `.gitignore` in that folder. The compose project
name is `devops-<project>`, the same one devops.sh uses, so both manage the
same containers. Tools on an existing server are not in the file.
