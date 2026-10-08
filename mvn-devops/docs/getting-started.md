# Getting started, step by step

Every guide starts in the root of your Maven project, with Docker running.
On Windows, use `mvn-devops\devops.bat` instead of `mvn-devops/devops.sh`.

- [Guide 1: a ready-made pipeline](#guide-1-a-ready-made-pipeline)
- [Guide 2: your own choice of tools](#guide-2-your-own-choice-of-tools)
- [Guide 3: Maven in a container on another machine](#guide-3-maven-in-a-container-on-another-machine)
- [Guide 4: a ready-made pipeline file of your own](#guide-4-a-ready-made-pipeline-file-of-your-own)
- [Guide 5: a teammate joins](#guide-5-a-teammate-joins)

## Before every guide

Step 1. Create a GitHub token with the `repo` scope (and `write:packages`).
See [github-setup.md](github-setup.md).

Step 2. Add mvn-devops to the project:

```bash
curl -fsSLO https://github.com/oss-community/mvn-devops/releases/download/v1.0.0/mvn-devops-1.0.0.zip
unzip -q mvn-devops-1.0.0.zip && mv mvn-devops-1.0.0 mvn-devops && rm mvn-devops-1.0.0.zip
```

Step 3. Check the prerequisites:

```bash
mvn-devops/devops.sh doctor
```

## Guide 1: a ready-made pipeline

Step 1. List the ready-made pipelines:

```bash
mvn-devops/devops.sh pipelines
```

Step 2. Set one up:

```bash
mvn-devops/devops.sh setup --pipeline jenkins-sonarqube-nexus-argocd
```

Questions:

| Question | Answer |
|---|---|
| GitHub username | your user (asked only when git does not know it) |
| GitHub token | the token from *Before every guide* |
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

Step 1. Choose the tools from the menu:

```bash
mvn-devops/devops.sh init
```

Questions (type the number, or several separated by commas):

| Menu | Example answer |
|---|---|
| Pipeline orchestrator | `jenkins` |
| Code quality | `sonarqube` |
| Artifact repositories | `nexus` |
| Container image | `docker-registry` |
| Deployment | `kubernetes` |
| The other menus | `0` for none |

Step 2. Answer the questions:

```bash
mvn-devops/devops.sh secrets
```

Press Enter to keep each default. Type only:

| Question | Answer |
|---|---|
| GitHub token | the token from *Before every guide* |
| Deployment environments, in the order the image goes through them | Enter for `staging production`, or e.g. `dev test staging prod` |
| Approval before deploying to `<environment>` | Enter (only the last one needs it) |
| Accept the Nexus Community Edition EULA? | `yes` (only with Nexus) |

Step 3. Start, configure and publish:

```bash
mvn-devops/devops.sh up
mvn-devops/devops.sh configure
mvn-devops/devops.sh publish
```

Step 4. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 5. Commit the settings:

```bash
git add devops.conf mvn-devops && git commit -m "Add mvn-devops"
```

## Guide 3: Maven in a container on another machine

The pipeline runs in a container with Java and Maven, which checks the
project out of GitHub. Nothing runs on your machine but `devops.sh`.

Step 1. Point Docker at the other machine (skip this step to use this one):

```bash
export DOCKER_HOST=ssh://user@build-vm
```

Step 2. Check that Docker answers:

```bash
mvn-devops/devops.sh doctor
```

Step 3. Push your commits; the container builds what is on GitHub:

```bash
git push
```

Step 4. Set it up:

```bash
mvn-devops/devops.sh setup --pipeline maven-container-kubernetes
```

Questions:

| Question | Answer |
|---|---|
| GitHub username | your user (asked only when git does not know it) |
| GitHub token | the token from *Before every guide* |

Step 5. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 6. Approve production:

```bash
mvn-devops/devops.sh run --phase prod
```

To choose the tools yourself instead of step 4, follow guide 2 and choose
`maven-container` as the orchestrator.

## Guide 4: a ready-made pipeline file of your own

Step 1. Write the file, for example `../team/jenkins-k8s.conf`:

```properties
# Jenkins and SonarQube with three environments.
ORCHESTRATOR=jenkins
TOOLS=sonarqube,docker-registry,kubernetes
ENVIRONMENTS=dev test prod
ENV_TEST_APPROVAL=yes
ENV_PROD_APPROVAL=yes
```

Step 2. Set it up:

```bash
mvn-devops/devops.sh setup --pipeline ../team/jenkins-k8s.conf
```

Questions: the GitHub token, and the Nexus EULA when Nexus is in `TOOLS`.

Step 3. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 4. Approve each environment that needs it, in order:

```bash
mvn-devops/devops.sh run --phase test
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

Questions: the GitHub username and token. Passwords are generated.

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

More in [troubleshooting.md](troubleshooting.md).
