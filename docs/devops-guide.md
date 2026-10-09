# <p align="center">DevOps Step by Step</p>

<p align="center">Running the Nexusphere pipeline with the mvn-devops ready-made pipelines.</p>

## <p align="center">Table of Content</p>

* [Before You Start](#before-you-start)
* [Maven with SonarQube and Nexus](#maven-with-sonarqube-and-nexus)
* [Jenkins Complete](#jenkins-complete)
* [Notes for This Repository](#notes-for-this-repository)
* [Every Day](#every-day)
* [Stop and Clean Up](#stop-and-clean-up)
* [Upgrade](#upgrade)

## Before You Start

Every step runs in the root of the repository with Docker running. On Windows use Git Bash or Cygwin and
`mvn-devops\devops.bat` instead of `mvn-devops/devops.sh`; machine settings are in
[Local Environment Setup](local-setup.md).

Step 1. Create a GitHub personal access token for `oss-community/nexusphere` with the `repo` scope, and
`write:packages` if GitHub Packages will be used. The pipeline clones the repository with it, so a private repository
needs it in every pipeline. See [mvn-devops/docs/github-setup.md](../mvn-devops/docs/github-setup.md).

Step 2. Check the prerequisites (Bash 4, Docker with compose, curl, jq, git, ssh-keygen, Java 21 and Maven):

```shell
mvn-devops/devops.sh doctor
```

Step 3. List the ready-made pipelines:

```shell
mvn-devops/devops.sh pipelines
```

## Maven with SonarQube and Nexus

<p style="text-align: justify;">

The simplest pipeline. Maven runs on this machine; SonarQube and Nexus run in Docker. The pipeline builds and tests
the project, sends it to SonarQube and publishes the artifacts to Nexus. Nothing is deployed.

</p>

Step 1. Set it up:

```shell
mvn-devops/devops.sh setup --pipeline maven-sonarqube-nexus
```

| Question                                 | Answer                                        |
|------------------------------------------|-----------------------------------------------|
| GitHub username                          | your GitHub user (asked only if git lacks it) |
| GitHub personal access token             | the token from step 1                         |
| Accept the Nexus Community Edition EULA? | `yes`                                         |
| Any other question                       | Enter, to keep the default                    |

Step 2. Run the pipeline:

```shell
mvn-devops/devops.sh run
```

Step 3. Open SonarQube and Nexus; the URLs and admin users are printed, passwords are in `.devops/`:

```shell
mvn-devops/devops.sh urls
```

Step 4. Commit the settings so a teammate gets the same tools (`.devops/` stays out of git):

```shell
git add devops.conf
git commit -m "Add the maven-sonarqube-nexus pipeline"
```

## Jenkins Complete

<p style="text-align: justify;">

The heaviest pipeline: Jenkins with SonarQube, Nexus, a Docker registry, Trivy, Syft, Cosign, Argo CD on Kubernetes,
Vault, PostgreSQL, Prometheus, Loki and k6. The image goes through `dev`, `test` and `staging` on every run, is
load-tested in `staging`, and goes to `prod` only when approved. Every tool starts at once, so give Docker plenty of
memory (16 GB or more) and disk. Jenkins checks the project out of GitHub, so push your commits first.

</p>

Step 1. Push the branch the pipeline will build:

```shell
git push
```

Step 2. Set it up:

```shell
mvn-devops/devops.sh setup --pipeline jenkins-complete
```

| Question                                 | Answer                                        |
|------------------------------------------|-----------------------------------------------|
| GitHub username                          | your GitHub user (asked only if git lacks it) |
| GitHub personal access token             | the token from step 1 of Before You Start     |
| Accept the Nexus Community Edition EULA? | `yes`                                         |
| Any other question                       | Enter, to keep the default                    |

Step 3. Run ci, cd, dev, test and staging:

```shell
mvn-devops/devops.sh run
```

Step 4. Approve production when staging looks right:

```shell
mvn-devops/devops.sh run --phase prod
```

Step 5. Open Jenkins, SonarQube, Nexus, Argo CD, Vault, Grafana and the other tools:

```shell
mvn-devops/devops.sh urls
```

Step 6. Put the previous image back if a release goes wrong:

```shell
mvn-devops/devops.sh rollback prod
```

Step 7. Commit the settings:

```shell
git add devops.conf
git commit -m "Add the jenkins-complete pipeline"
```

## Notes for This Repository

<p style="text-align: justify;">

`devops.sh stages` shows what a pipeline will run before anything starts. For `jenkins-complete` two stages assume a
single-module project and have not been run against this multi-module one yet: `migrate` points Flyway at
`src/main/resources/db/migration` in the root, while the migrations of this repository live in
`ledger/server/src/main/resources/db/migration/ledger`, and `image` runs Jib from the root, but the executable jar is
built by `ledger/server`. Expect to adjust these two stages on the first run.

</p>

## Every Day

| Command                                      | Does                                                  |
|----------------------------------------------|-------------------------------------------------------|
| `mvn-devops/devops.sh status`                | Shows the selected tools and whether they are running |
| `mvn-devops/devops.sh stages`                | Prints the stages and the exact commands they run     |
| `mvn-devops/devops.sh run`                   | Runs the pipeline                                     |
| `mvn-devops/devops.sh urls`                  | Prints the tool URLs and users                        |
| `mvn-devops/devops.sh secrets --reconfigure` | Answers every question again                          |

## Stop and Clean Up

```shell
mvn-devops/devops.sh down
mvn-devops/devops.sh destroy
```

`down` stops the tools and keeps their data; `destroy` asks before removing their containers and volumes, and again
before deleting the stored values in `.devops/`.

## Upgrade

```shell
mvn-devops/devops.sh upgrade --check
mvn-devops/devops.sh upgrade
```

`upgrade` replaces the `mvn-devops` folder with the latest release after checking it against the release's
`SHA256SUMS`. Commit the folder afterwards.

##

**<p align="center">[Top](#devops-step-by-step)</p>**
