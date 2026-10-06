# mvn-devops

A modular DevOps framework for Maven projects, written in plain Bash.

You pick the tools from a menu, mvn-devops starts them in Docker, configures
them (admin passwords, tokens, repositories) and turns the stages they
contribute into a pipeline for the orchestrator you chose: plain `mvn` on your
machine, Jenkins or Concourse.

```
$ ./devops.sh -p ../my-maven-project init

Pipeline orchestrator (choose one)
  1) concourse        Concourse CI in Docker: a ci job on every push, a manual cd job
  2) jenkins          Jenkins in Docker, configured as code (no setup wizard)
  3) maven            Run the stages with mvn directly on this machine
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

## Prerequisites

- Bash 4+ (Linux, macOS, or Git Bash on Windows; `devops.bat` finds it for you)
- Docker with the compose plugin
- `curl`, `jq`, `git`, `ssh-keygen`
- Java and Maven when the `maven` orchestrator runs the pipeline locally

`./devops.sh doctor` checks all of them.

## Install

Every release on the [releases page](https://github.com/oss-community/mvn-devops/releases) has:

| File | For |
|---|---|
| `mvn-devops_<version>_all.deb` | Debian, Ubuntu: `sudo apt install ./mvn-devops_<version>_all.deb` |
| `mvn-devops-<version>-1.noarch.rpm` | Fedora, RHEL, Rocky: `sudo dnf install ./mvn-devops-<version>-1.noarch.rpm` |
| `mvn-devops-<version>.zip` | Windows: unzip, add the folder to `PATH`, run `devops.bat` (needs Git for Windows) |
| `mvn-devops-<version>.tar.gz` | macOS or any Linux: unpack and add the folder to `PATH` |
| `SHA256SUMS` | checksums of the files above |

The Linux packages install into `/usr/share/mvn-devops` and add the command
`mvn-devops`. With the zip or tar.gz the command is `devops.bat` or
`devops.sh` in the unpacked folder. A `git clone` of this repository works
the same way. `mvn-devops --version` prints the installed version.

### Shipping it with the project (zip)

mvn-devops can live inside the project and be committed with it, so everyone
who clones the project gets the same DevOps setup, like the Maven wrapper.

**1. Download and extract** `mvn-devops-<version>.zip` from the
[releases page](https://github.com/oss-community/mvn-devops/releases) into the
project root, and rename the folder to `mvn-devops` so paths stay the same
across upgrades.

Linux, macOS, Git Bash:

```bash
cd my-maven-project
curl -fsSLO https://github.com/oss-community/mvn-devops/releases/download/v1.0.0/mvn-devops-1.0.0.zip
unzip -q mvn-devops-1.0.0.zip && mv mvn-devops-1.0.0 mvn-devops && rm mvn-devops-1.0.0.zip
```

Windows PowerShell (or right-click the zip > Extract All, then rename the folder):

```powershell
cd my-maven-project
Invoke-WebRequest https://github.com/oss-community/mvn-devops/releases/download/v1.0.0/mvn-devops-1.0.0.zip -OutFile mvn-devops.zip
Expand-Archive mvn-devops.zip -DestinationPath . ; Rename-Item mvn-devops-1.0.0 mvn-devops ; Remove-Item mvn-devops.zip
```

The project then looks like this:

```
my-maven-project/
  pom.xml
  mvn-devops/          committed: devops.sh, devops.bat, lib/, modules/, ...
  .devops/             created by setup, never committed (passwords, tokens)
```

**2. Commit it:**

```bash
git add mvn-devops && git commit -m "Add mvn-devops 1.0.0"
```

**3. Use it from the project root:**

```bash
mvn-devops/devops.sh setup        # Windows: mvn-devops\devops.bat setup
mvn-devops/devops.sh run
```

Everyone else clones the project and runs `mvn-devops/devops.sh setup`; each
person answers the questions for their own machine.

**Upgrade** by deleting the `mvn-devops` folder, extracting the new zip the
same way and committing. `mvn-devops/devops.sh --version` shows the version in
use.

Notes:

- The folder has no `pom.xml`, so Maven, Sonar and the pipeline ignore it.
- Use the zip or tar.gz, not a `git clone`, which would put a repository
  inside the project's repository.
- On Linux and macOS keep the executable bit when committing (`unzip` and git
  keep it). If `devops.sh` lost it, run
  `git update-index --chmod=+x mvn-devops/devops.sh`.
- Line endings: Bash cannot run scripts with Windows (CRLF) line endings, and
  git on Windows (`core.autocrlf=true`) converts files to CRLF on checkout.
  The zip contains `mvn-devops/.gitattributes`, which keeps every file LF (and
  `devops.bat` CRLF) whatever `core.autocrlf` is, so commit it together with
  the folder. `devops.sh doctor` reports files that are already CRLF and prints
  the command that fixes them. A project that committed mvn-devops before this
  file existed adds it from the new zip, then fixes its checkout once with:

  ```bash
  git add --renormalize mvn-devops && git commit -m "Normalize mvn-devops line endings"
  rm -rf mvn-devops && git checkout -- mvn-devops
  ```

## Quick start

First create the GitHub token(s) and, for site publishing with the `maven`
orchestrator, an SSH key: see [docs/github-setup.md](docs/github-setup.md).
Install the tools listed above ([docs/prerequisites.md](docs/prerequisites.md)).
IntelliJ settings that match the pipeline are in [docs/ide.md](docs/ide.md).

```bash
cd my-maven-project
mvn-devops setup      # menu, questions, containers, tokens, pipeline
mvn-devops run        # run the pipeline
mvn-devops urls       # web consoles and how to log in
```

or from anywhere with `mvn-devops -p ~/work/my-maven-project setup`. The only
thing written into the project is the `.devops/` folder, which keeps itself out
of git.

`setup` is the five steps below in order; each can also be run on its own.

| Step | Command | What happens |
|---|---|---|
| 1 | `init` | Menu per category. Saves the choice in `.devops/profile.conf`. |
| 2 | `secrets` | Each selected module asks for the values it needs. Passwords default to random ones. |
| 3 | `up` | Modules prepare (render config, keys), then `docker compose up` with every selected module's compose fragment. |
| 4 | `configure` | Modules finish their tool: change default admin passwords, create tokens and repositories. |
| 5 | `publish` | The orchestrator renders the pipeline and installs it (Jenkins job, Concourse pipeline, local script). |

Without questions, for scripts and CI:

```bash
./devops.sh -y -p ../my-maven-project init --orchestrator jenkins --with sonarqube,nexus,github-pages
./devops.sh -y -p ../my-maven-project setup
```

## Commands

| Command | |
|---|---|
| `init [--orchestrator X --with a,b]` | choose modules |
| `setup` | init (if needed) + secrets + up + configure + publish |
| `secrets [--reconfigure]` | ask for values; `--reconfigure` asks again |
| `up`, `configure`, `publish` | single setup steps |
| `stages` | the ordered stages contributed by the selected modules |
| `render` | write pipeline files into `.devops/generated` |
| `run` | run the pipeline. maven: `--dry-run`, `--from <stage>`, `--only <stage>`, `--phase ci\|cd`. concourse: `--phase ci\|cd` |
| `status`, `urls`, `logs [service]`, `compose ...` | operations |
| `down` | stop containers, keep data |
| `destroy` | remove containers and volumes, the deploy key and webhook on GitHub, optionally the stored values |
| `export-compose [dir]` | write one `docker-compose.yml` and its `.env` for the selected tools (default `.devops/compose`) |
| `release [--version X] [--next Y] [--dry-run] [--no-push]` | release the project, see [Releasing your project](#releasing-your-project) |
| `env [--show\|--windows]` | regenerate env files; `--show` masks secrets; `--windows` writes a `setx` script for IDEs on Windows |
| `get <KEY>` | print one stored value, e.g. `get SONAR_ADMIN_PASSWORD` |
| `modules`, `doctor` | list modules, check prerequisites |

## Modules

| Category | Mode | Modules |
|---|---|---|
| Source control | required | `github` |
| Build | required | `maven` (validate, package, test, checkstyle, install) |
| Pipeline orchestrator | one | `maven`, `jenkins`, `concourse` |
| Code quality | any | `sonarqube` |
| Artifact repositories | any | `jfrog`, `nexus`, `github-packages` |
| Project site | any | `github-pages` |

**Artifactory OSS** does not allow creating repositories through its API.
After `configure`, open Artifactory, choose Quick Setup > Maven and enter the
repository prefix you gave in `secrets` (`JFROG_ARTIFACTORY_REPOSITORY_PREFIX`),
so `<prefix>-libs-release-local` and `<prefix>-libs-snapshot-local` exist.
`configure` creates them itself on the Pro editions.

Default stages with every module selected (plugin coordinates shortened):

```
ORDER  PHASE STAGE            MAVEN ARGUMENTS
10     ci   validate         validate
20     ci   build            clean package -DskipTests=true
30     ci   test             test
40     ci   checkstyle       maven-checkstyle-plugin:3.6.0:check -Dcheckstyle.config.location=google_checks.xml
45     ci   sonar            sonar-maven-plugin:4.0.0.4121:sonar -Dsonar.host.url=$SONAR_URL -Dsonar.token=$SONAR_TOKEN
50     ci   install          install -DskipTests=true
60     cd   site             maven-site-plugin:3.21.0:site
62     cd   stage-site       (shell) copy the root and module sites into target/staging
65     cd   publish-site     -N maven-scm-publish-plugin:3.3.0:publish-scm -Dscmpublish.pubScmUrl=... -Dscmpublish.scmBranch=site
70     cd   deploy-jfrog     package source:jar-no-fork javadoc:jar maven-deploy-plugin:3.1.3:deploy -DaltSnapshotDeploymentRepository=jfrog-snapshots::$JFROG_ARTIFACTORY_SNAPSHOT_URL ...
71     cd   deploy-github    ... -DaltSnapshotDeploymentRepository=github::$GITHUB_PACKAGES_URL ...
72     cd   deploy-nexus     ... -DaltSnapshotDeploymentRepository=nexus-snapshots::$NEXUS_ARTIFACTORY_SNAPSHOT_URL ...
```

**The project's pom.xml needs no profiles, no distributionManagement and no
settings file.** Every plugin is called by its coordinates and configured with
`-D` properties, and credentials come from the framework's own
[settings.xml](templates/settings.xml), passed as global settings (`-gs`).
Details and optional knobs (extra profiles, checkstyle rules, plugin versions)
are in [docs/project-requirements.md](docs/project-requirements.md).

Adding a tool is one directory; see [docs/module-guide.md](docs/module-guide.md).

## Project site on GitHub Pages

The `github-pages` module builds the Maven site and pushes it to a `site`
branch, which GitHub Pages serves. Two things have to be done once by hand.

**1. Create the `site` branch** in your project repository. It is an orphan
branch that only holds the published site:

```bash
git checkout --orphan site
git rm -rf --cached . > /dev/null
echo "site" > index.html
git add index.html
git commit -m "Initialize site"
git push origin site
git checkout -f main
```

`git checkout -f main` restores your working tree; untracked files such as
`.devops/` are left alone. Use another branch name by answering the
`SITE_BRANCH` question in `secrets`.

**2. Point GitHub Pages at it.** In the GitHub repository go to
Settings > Pages, and under Build and deployment set:

- Source: Deploy from a branch
- Branch: `site`, folder `/ (root)`

The site is then served at `https://<owner>.github.io/<repo>/`.

**Publishing.** The site stages run in the `cd` phase, after the ci stages:

```bash
./devops.sh run --phase cd            # site, stage-site, publish-site and the deploys
./devops.sh run --only publish-site   # push an already built and staged site again
```

**Maven by hand.** The same steps as plain Maven commands, run in the project
root. No profiles or settings are needed:

```bash
# build the site, including module sites
mvn -B org.apache.maven.plugins:maven-site-plugin:3.21.0:site

# preview it at http://localhost:8000
mvn org.apache.maven.plugins:maven-site-plugin:3.21.0:run -Dport=8000

# publish target/staging to the site branch (after ./devops.sh run --only stage-site)
mvn -B -N org.apache.maven.plugins:maven-scm-publish-plugin:3.3.0:publish-scm \
  -Dscmpublish.pubScmUrl=scm:git:git@github.com:<owner>/<repo>.git \
  -Dscmpublish.scmBranch=site
```

`./devops.sh stages` prints the exact command of every stage for your
selection. Pushing uses SSH: your own key with the `maven` orchestrator, and a
deploy key that `configure` registers on the repository with Jenkins or
Concourse.

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

All state for a project is kept in `<project>/.devops/` (it git-ignores itself):

```
.devops/
  profile.conf          selected modules
  values/<KEY>          one file per value, chmod 600
  env/pipeline.env      variables handed to the pipeline (compose env_file format)
  env/pipeline.sh       the same as bash exports
  env/compose.env       everything, for ${VAR} substitution in compose files
  generated/            Jenkinsfile, jenkins/casc.yaml, concourse/*.yml, pipeline.sh
  keys/                 generated SSH deploy key
```

Nothing is written to `~/.bashrc` or to system environment variables, and
secrets are never printed: `env --show` masks them and the orchestrators bind
them as masked credentials.

URLs handed to the pipeline depend on where it runs. A tool on an existing
server is always reached at its own URL. A tool in Docker is reached at
`DEVOPS_HOST:<port>` by the `maven` orchestrator and by an existing Jenkins or
Concourse server, and at its compose service name (`http://sonarqube:9000`) by
Jenkins or Concourse in Docker, which share its network. See
[Where the tools run](#where-the-tools-run).

## Orchestrators

**maven** runs each stage with `mvn` on your machine, with the pipeline
variables exported only for that process. `render` also writes
`.devops/generated/pipeline.sh` for IDE run configurations.

**jenkins** builds an image from `jenkins/jenkins:lts-jdk21` with Maven 3.9
(copied from the official Maven image) and the needed plugins, skips the setup wizard and configures everything with
Configuration as Code: the admin user, one secret-text credential per secret and
a pipeline job generated from the stages. `run` triggers the job and streams its
console. There is no login to the UI and no API token to create by hand:
devops.sh talks to its own Jenkins with the admin password over the REST API.

Builds also start on every push, chosen with `JENKINS_TRIGGER` in `secrets`:
`poll` (default) checks the repository every two minutes, `webhook` registers
a GitHub webhook to `JENKINS_PUBLIC_URL/github-webhook/` (Jenkins must be
reachable from GitHub, see [docs/ngrok.md](docs/ngrok.md), and the token needs
`admin:repo_hook`), `none` builds only on `run`. Either trigger starts working
after the first build, which records the repository.

**concourse** runs `concourse quickstart` (web and worker in one container).
The pipeline has a `ci` job, triggered by every push, and a `cd` job that runs
all stages and is started by hand after `ci` passed. Tasks run in
`maven:3.9-eclipse-temurin-21`. `fly` is downloaded from the server.

All orchestrators use the same Java and Maven, set with `JAVA_VERSION` (21)
and `MAVEN_VERSION` (3.9) in `secrets`. Java 21 also builds projects whose pom
targets an older release such as 17.

## Docker images

Every tool runs from its official image, unchanged:

| Tool | Image |
|---|---|
| SonarQube | `sonarqube` (with `postgres:16`) |
| Nexus | `sonatype/nexus3` |
| Artifactory OSS | `releases-docker.jfrog.io/jfrog/artifactory-oss` (with `postgres:16`) |
| Concourse | `concourse/concourse` (with `postgres:16`) |
| Concourse build tasks | `maven:<MAVEN_VERSION>-eclipse-temurin-<JAVA_VERSION>` |

Jenkins is the one exception. The official `jenkins/jenkins` image has Java but
no Maven, and the pipeline stages are `mvn` commands run inside Jenkins. So
[`modules/orchestrator/jenkins/docker/Dockerfile`](modules/orchestrator/jenkins/docker/Dockerfile)
adds a thin layer built only from official images: it starts from
`jenkins/jenkins:lts-jdk<JAVA_VERSION>`, copies Maven from
`maven:<MAVEN_VERSION>-eclipse-temurin-<JAVA_VERSION>`, and adds git, ssh, curl,
jq and the plugins in `plugins.txt`. `up` builds it locally; no image is
published by mvn-devops. Java and Maven versions come from `JAVA_VERSION`
(default 21) and `MAVEN_VERSION` (default 3.9), asked by the `maven` build
module, so Jenkins, Concourse and your machine use the same versions.

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

## Where the tools run

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

### Tools in Docker on another machine

Clone mvn-devops and the project on the VM and run `devops.sh` there, or point
Docker at the VM and run everything from your machine:

```bash
export DOCKER_HOST=ssh://user@build-vm      # or a docker context
../mvn-devops/devops.sh doctor              # docker daemon reachable on build-vm
../mvn-devops/devops.sh setup               # containers start on the VM
../mvn-devops/devops.sh run                 # mvn runs here and talks to build-vm:<port>
```

`secrets` takes the default for `DEVOPS_HOST` from `DOCKER_HOST` or the docker
context. The ports must be reachable from where the pipeline runs (firewall,
security group). Jenkins in Docker mounts its configuration from `.devops/`,
which a remote Docker daemon cannot see, so run `devops.sh` on the VM for it.

When an existing Jenkins or Concourse server runs the pipeline and some tools
run in Docker, set `DEVOPS_HOST` to an address that server can reach;
`publish` warns when it is `localhost`.

## Releasing your project

`devops.sh release` releases the Maven project without maven-release-plugin, so
the pom needs no `<scm>` and no `<distributionManagement>`:

1. sets the release version in every module (`1.2.0-SNAPSHOT` becomes `1.2.0`),
   commits "Release 1.2.0" and tags `v1.2.0`
2. runs the deploy stages of the selected artifact repositories
3. sets the next development version (`1.2.1-SNAPSHOT`) and commits it
4. pushes the branch and the tag

```bash
devops.sh release --dry-run                          # show what would happen
devops.sh release                                    # 1.2.0, then 1.2.1-SNAPSHOT
devops.sh release --version 2.0.0 --next 2.1.0-SNAPSHOT
devops.sh release --no-push                          # check locally, push yourself
```

It runs on your machine with `mvn` and git, needs a clean working tree and
pushes with your own git credentials or SSH key. Nothing is pushed before
step 4: if a step fails, the release commit and the tag are rolled back. To
undo a release that was already pushed, delete the tag
(`git push origin :refs/tags/v1.2.0`) and revert the two commits.

## Releasing mvn-devops

Set the version in `VERSION`, commit, and push a tag, or start the release
workflow by hand under Actions > release > Run workflow with the version:

```bash
git tag v1.1.0 && git push origin v1.1.0
```

The release workflow runs the checks, builds the files with
`packaging/build.sh` (zip, tar.gz, and deb and rpm with
[nfpm](https://nfpm.goreleaser.com)), installs the deb as a check and publishes
everything as a GitHub release. `packaging/build.sh` also works locally and
writes to `dist/`.

## Testing

```bash
tests/smoke.sh            # every orchestrator: init, secrets, stages, render, compose config
shellcheck devops.sh lib/*.sh modules/*/*/module.sh tests/*.sh
```

## License

Apache License 2.0
