# Orchestrators

The orchestrator runs the pipeline's stages. mvn-devops has four: maven,
maven-container, jenkins and concourse. This page shows how to run the
pipeline and approve an environment with each, and which Docker images they
use. On Windows, run `mvn-devops\devops.bat` instead of `mvn-devops/devops.sh`.

## maven

maven runs each stage with `mvn` on your machine, with the pipeline variables
exported only for that process.

Step 1. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 2. Approve the environment where `run` stopped:

```bash
mvn-devops/devops.sh run --phase <environment>
```

## maven-container

maven-container runs the same stages with `mvn` in a container from the
official image `maven:<MAVEN_VERSION>-eclipse-temurin-<JAVA_VERSION>`
(`MAVEN_IMAGE`). It needs neither Java, Maven nor the checkout of this
machine.

Step 1. Push your commits, because the container checks the branch out of GitHub:

```bash
git push
```

Step 2. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 3. Approve the environment where `run` stopped:

```bash
mvn-devops/devops.sh run --phase <environment>
```

## jenkins

jenkins runs the pipeline as a Jenkins job that mvn-devops creates and
configures. There is no login to the UI and no API token to create by hand.

Step 1. Run the pipeline and follow its console:

```bash
mvn-devops/devops.sh run
```

Step 2. Approve the environment at the `approve-<environment>` stage, or choose *Deploy* in the build:

```bash
mvn-devops/devops.sh run --phase <environment>
```

## concourse

concourse runs the pipeline in Concourse: a `ci` job on every push, a `cd`
job you start by hand, and one job per environment that needs approval.

Step 1. Push your commits, which start the `ci` job:

```bash
git push
```

Step 2. Start the `cd` job after `ci` passed, or press its + button in Concourse:

```bash
mvn-devops/devops.sh run --phase cd
```

Step 3. Approve the environment after the job before it passed, or press the job's + button:

```bash
mvn-devops/devops.sh run --phase <environment>
```

## Approvals

A deployment environment that needs approval (by default the last one, see
[Environments](deployment.md#environments)) runs its stages, and those of the
environments after it up to the next approval, only after someone approves
it, in the way each orchestrator offers:

| Orchestrator | Waiting | Approve with |
|---|---|---|
| maven, maven-container | `run` stops before the environment | `mvn-devops/devops.sh run --phase <environment>` |
| jenkins | the build stops at the `approve-<environment>` stage (an `input` step, kept for 7 days) | `mvn-devops/devops.sh run --phase <environment>`, or *Deploy* in the build |
| concourse | the environment's job, after the job before it passed | `mvn-devops/devops.sh run --phase <environment>`, or the job's + button |

`prod` stands for the last environment. An approved environment gets the
commit that passed the environments before it; it is not built again.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `JAVA_VERSION` | `21` | Java of every orchestrator, asked in `secrets` by the `maven` build module |
| `MAVEN_VERSION` | `3.9` | Maven of every orchestrator, asked in `secrets` by the `maven` build module |
| `MAVEN_IMAGE` | `maven:<MAVEN_VERSION>-eclipse-temurin-<JAVA_VERSION>` | image the maven-container stages and the Concourse build tasks run in |
| `GIT_BRANCH` | the current branch | branch the pipeline builds; maven-container checks it out of GitHub |
| `JENKINS_TRIGGER` | `poll` | how Jenkins starts a build on every push: `poll`, `webhook` or `none` |
| `JENKINS_PUBLIC_URL` | `JENKINS_SERVER_URL` | public URL of Jenkins for the GitHub webhook, asked only for `webhook` |

Java 21 also builds projects whose pom targets an older release such as 17.

`JENKINS_TRIGGER` values:

- `poll` checks the repository every two minutes.
- `webhook` registers a GitHub webhook to `JENKINS_PUBLIC_URL/github-webhook/`. Jenkins must be reachable from GitHub ([ngrok.md](ngrok.md)), and the token needs `admin:repo_hook`.
- `none` builds only on `run`.

Either trigger starts working after the first build, which records the
repository.

## Docker images

Every tool runs from its official image, unchanged:

| Tool | Image |
|---|---|
| SonarQube | `sonarqube:26.9.0.129388-community` (with `postgres:18`) |
| Nexus | `sonatype/nexus3` |
| Artifactory OSS | `releases-docker.jfrog.io/jfrog/artifactory-oss` (with `postgres:18`) |
| Concourse | `concourse/concourse` (with `postgres:18`) |
| Concourse build tasks, maven-container | `maven:<MAVEN_VERSION>-eclipse-temurin-<JAVA_VERSION>` |
| Docker registry | `registry:3` |
| Simulated deploy machine | built from `docker:cli` with openssh-server and curl |
| Kubernetes | `rancher/k3s:v1.37.1-k3s1` (`K3S_IMAGE_TAG`) |
| Argo CD, Argo Rollouts | the projects' release manifests (3.5.4, 1.10.0), applied to the cluster |

Jenkins is the one exception. The official `jenkins/jenkins` image has Java but
no Maven, and the pipeline stages are `mvn` commands run inside Jenkins. So
[`modules/orchestrator/jenkins/docker/Dockerfile`](../modules/orchestrator/jenkins/docker/Dockerfile)
adds a thin layer built only from official images: it starts from
`jenkins/jenkins:lts-jdk<JAVA_VERSION>`, copies Maven from
`maven:<MAVEN_VERSION>-eclipse-temurin-<JAVA_VERSION>`, and adds git, ssh, curl,
jq and the plugins in `plugins.txt`. `up` builds it locally; no image is
published by mvn-devops. Java and Maven versions come from `JAVA_VERSION`
(default 21) and `MAVEN_VERSION` (default 3.9), asked by the `maven` build
module, so Jenkins, Concourse and your machine use the same versions.

Only stable releases are used. SonarQube Community Build has no long-term
release and a new version each month, so it is pinned (`SONAR_IMAGE_TAG`
overrides it); the other tools follow their stable `latest` or `lts` tag.
SonarQube refuses to start when the disk holding Docker is more than 90% full.

## How it works

**maven.** `render` also writes `.devops/generated/pipeline.sh` for IDE run
configurations.

**maven-container.** `run` starts the container on the Docker machine: this
one, or the one `DOCKER_HOST` points to ([where-tools-run.md](where-tools-run.md)).
The container checks the branch (`GIT_BRANCH`) out of GitHub. It joins the
network of the tools and reaches them by their service names, as Jenkins and
Concourse do, and a volume keeps the Maven repository and the downloaded tools
between runs. `run` takes the same options as with maven;
`run --phase <environment>` deploys the commit that passed the part of the
pipeline before it, not the newest one. `destroy` removes the volume.

**jenkins.** mvn-devops builds an image from `jenkins/jenkins:lts-jdk21` with
Maven 3.9 (copied from the official Maven image) and the needed plugins, skips
the setup wizard and configures everything with Configuration as Code: the
admin user, one secret-text credential per secret and a pipeline job generated
from the stages. `run` triggers the job and streams its console. devops.sh
talks to its own Jenkins with the admin password over the REST API. Builds also
start on every push, as `JENKINS_TRIGGER` sets.

**concourse.** mvn-devops runs `concourse quickstart` (web and worker in one
container). The pipeline has a `ci` job, triggered by every push; a `cd` job
that runs the ci and cd stages and deploys to the environments before the
first approval, started by hand after `ci` passed; and a job per environment
that needs approval ([Approvals](#approvals)). Tasks run in
`MAVEN_IMAGE`, by default `maven:3.9-eclipse-temurin-21`. `fly` is downloaded from the server.

Stage scripts (`templates/scripts`) and the Helm chart (`templates/helm`) reach
maven-container, Jenkins and Concourse as a compressed copy in the generated
pipeline, unpacked into `.devops/` of the checkout before the first stage.

## Next

- [Deployment](deployment.md): environments, approvals and deploy targets.
- [Where the tools run](where-tools-run.md): run the tools on another machine or use existing servers.
- [ngrok](ngrok.md): let GitHub reach Jenkins for the push webhook.
