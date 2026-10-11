# Getting started

This page walks you through your first pipeline, one command per step. Pick
the guide that fits: a ready-made pipeline, your own choice of tools, Maven in
a container on another machine, a pipeline file of your own, or joining a
project a teammate already set up. Every guide starts in the root of your
Maven project, with Docker running. On Windows, use `mvn-devops\devops.bat`
instead of `mvn-devops/devops.sh`.

- [Guide 1: a ready-made pipeline](#guide-1-a-ready-made-pipeline)
- [Guide 2: your own choice of tools](#guide-2-your-own-choice-of-tools)
- [Guide 3: Maven in a container on another machine](#guide-3-maven-in-a-container-on-another-machine)
- [Guide 4: a ready-made pipeline file of your own](#guide-4-a-ready-made-pipeline-file-of-your-own)
- [Guide 5: a teammate joins](#guide-5-a-teammate-joins)

## Before you start

- A GitHub token with the `repo` scope (and `write:packages`): [GitHub setup](github-setup.md).
- mvn-devops in the project root: [Installation](installation.md#shipping-it-with-the-project-zip).
- The prerequisites installed, and `mvn-devops/devops.sh doctor` reporting no problems: [Prerequisites](prerequisites.md).

## Guide 1: a ready-made pipeline

Step 1. List the ready-made pipelines:

```bash
mvn-devops/devops.sh pipelines
```

Step 2. Set one up:

```bash
mvn-devops/devops.sh setup --pipeline jenkins-complete
```

Questions:

| Question | Answer |
|---|---|
| GitHub username | your user (asked only when git does not know it) |
| GitHub token | the token from *Before you start* |
| Accept the Nexus Community Edition EULA? | `yes` (only with Nexus) |

Step 3. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 4. Approve production when you want it deployed:

```bash
mvn-devops/devops.sh run --phase prod
```

Step 5. Open the tools:

```bash
mvn-devops/devops.sh urls
```

Step 6. Commit the settings:

```bash
git add devops.conf mvn-devops && git commit -m "Add mvn-devops"
```

## Guide 2: your own choice of tools

Step 1. Choose the tools from the menu, typing the number, or several separated by commas:

```bash
mvn-devops/devops.sh init
```

Questions:

| Question | Answer |
|---|---|
| Pipeline orchestrator | for example `jenkins` |
| Code quality | for example `sonarqube` |
| Artifact repositories | for example `nexus` |
| Container image | for example `docker-registry` |
| Deployment | for example `kubernetes` |
| The other menus | `0` for none |

Step 2. Answer the questions, pressing Enter to keep each default except for these:

```bash
mvn-devops/devops.sh secrets
```

Questions:

| Question | Answer |
|---|---|
| GitHub token | the token from *Before you start* |
| Deployment environments, in the order the image goes through them | Enter for `staging production`, or e.g. `dev test staging prod` |
| Approval before deploying to `<environment>` | Enter (only the last one needs it) |
| Accept the Nexus Community Edition EULA? | `yes` (only with Nexus) |

Step 3. Start the tools:

```bash
mvn-devops/devops.sh up
```

Step 4. Configure the tools:

```bash
mvn-devops/devops.sh configure
```

Step 5. Publish the pipeline:

```bash
mvn-devops/devops.sh publish
```

Step 6. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 7. Commit the settings:

```bash
git add devops.conf mvn-devops && git commit -m "Add mvn-devops"
```

## Guide 3: Maven in a container on another machine

The pipeline runs in a container with Java and Maven, which checks the
project out of GitHub. Nothing runs on your machine but `devops.sh`. To choose
the tools yourself instead of step 4, follow guide 2 and choose
`maven-container` as the orchestrator.

Step 1. Point Docker at the other machine, or skip this step to use this one:

```bash
export DOCKER_HOST=ssh://user@build-vm
```

Step 2. Check that Docker answers:

```bash
mvn-devops/devops.sh doctor
```

Step 3. Push your commits, because the container builds what is on GitHub:

```bash
git push
```

Step 4. Set it up:

```bash
mvn-devops/devops.sh setup --pipeline maven-basic
```

Questions:

| Question | Answer |
|---|---|
| GitHub username | your user (asked only when git does not know it) |
| GitHub token | the token from *Before you start* |

Step 5. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 6. Open the application on the machine:

```bash
mvn-devops/devops.sh urls
```

## Guide 4: a ready-made pipeline file of your own

Step 1. Write the file, for example `../team/jenkins-k8s.conf`, with the orchestrator, the tools as `--with` takes them, any setting that is neither secret nor personal, and a first comment line that describes it:

```properties
# Jenkins and SonarQube with three environments.
ORCHESTRATOR=jenkins
TOOLS=sonarqube,docker-registry,kubernetes
ENVIRONMENTS=dev test prod
ENV_TEST_APPROVAL=yes
ENV_PROD_APPROVAL=yes
KUBERNETES_REPLICAS=1
```

Step 2. Set it up, keeping the file anywhere, for example in a repository your team shares:

```bash
mvn-devops/devops.sh setup --pipeline ../team/jenkins-k8s.conf
```

Questions:

| Question | Answer |
|---|---|
| GitHub token | the token from *Before you start* |
| Accept the Nexus Community Edition EULA? | `yes` (only when Nexus is in `TOOLS`) |

Step 3. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 4. Approve the `test` environment:

```bash
mvn-devops/devops.sh run --phase test
```

Step 5. Approve the `prod` environment:

```bash
mvn-devops/devops.sh run --phase prod
```

## Guide 5: a teammate joins

Step 1. Clone the project:

```bash
git clone https://github.com/acme/app.git && cd app
```

Step 2. Set it up; the tools come from the committed `devops.conf`:

```bash
mvn-devops/devops.sh setup
```

Questions:

| Question | Answer |
|---|---|
| GitHub username | your user |
| GitHub token | your own token ([GitHub setup](github-setup.md)) |
| Passwords | nothing to type: they are generated |

Step 3. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

## When something goes wrong

| Problem | Command |
|---|---|
| A stage failed | `mvn-devops/devops.sh run --from <stage>` |
| See the stages | `mvn-devops/devops.sh stages` |
| Logs of a tool | `mvn-devops/devops.sh logs <service>` |
| Answer the questions again | `mvn-devops/devops.sh secrets --reconfigure` |
| Stop everything | `mvn-devops/devops.sh down` |
| Remove everything | `mvn-devops/devops.sh destroy` |

## Next

- [Ready-made pipelines](pipelines.md): the tested combinations of tools, and the settings of a pipeline file
- [Orchestrators](orchestrators.md): how maven, maven-container, Jenkins and Concourse run the stages
- [Troubleshooting](troubleshooting.md): common errors and their fixes
