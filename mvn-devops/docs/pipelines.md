# Ready-made pipelines

A ready-made pipeline is a tested combination of tools with its settings,
in the [pipelines](../pipelines) folder. Choose one instead of going through
the menu and the questions:

```bash
mvn-devops/devops.sh pipelines                              # what there is
mvn-devops/devops.sh setup --pipeline jenkins-sonarqube-nexus
```

`setup` then asks only what cannot be chosen for you: your GitHub user and
token, and whether you accept the Nexus licence when Nexus is part of it.
Every password, token and key is generated for your project, so two projects
set up from the same pipeline share no secrets. The images of the tools are
downloaded when they start, as with any other setup.

| Pipeline | Orchestrator | Tools | Environments |
|---|---|---|---|
| `maven-sonarqube-nexus` | maven | SonarQube, Nexus | |
| `jenkins-sonarqube-nexus` | Jenkins | SonarQube, Nexus | |
| `concourse-sonarqube-nexus` | Concourse | SonarQube, Nexus | |
| `maven-docker-host` | maven | registry, Docker Compose over SSH | dev test staging prod |
| `jenkins-kubernetes` | Jenkins | SonarQube, Nexus, registry, Trivy, Syft, Cosign, Kubernetes | dev test staging prod |
| `jenkins-observability` | Jenkins | registry, Docker Compose over SSH, PostgreSQL, Prometheus, Loki, k6 | dev test staging prod |
| `concourse-argocd` | Concourse | registry, Argo CD, Vault, Sealed Secrets | dev test staging prod |
| `jenkins-sonarqube-nexus-argocd` | Jenkins | SonarQube, Nexus, registry, Argo CD | dev test staging prod |
| `maven-container-sonarqube-nexus` | maven-container | SonarQube, Nexus | |
| `maven-container-kubernetes` | maven-container | registry, Trivy, Kubernetes, PostgreSQL | dev staging prod |
| `maven-kubernetes` | maven | registry, Kubernetes | dev test staging prod |
| `concourse-kubernetes-vault` | Concourse | SonarQube, registry, Kubernetes, Vault, PostgreSQL | dev test staging prod |
| `jenkins-argocd-observability` | Jenkins | registry, Argo CD, Prometheus, Loki, k6 | dev test staging prod |
| `concourse-docker-host-secure` | Concourse | registry, Trivy, Syft, Cosign, Docker Compose over SSH, PostgreSQL | dev staging prod |
| `maven-sonarqube-github` | maven | SonarQube, GitHub Packages, GitHub Pages | |
| `jenkins-complete` | Jenkins | SonarQube, Nexus, registry, Trivy, Syft, Cosign, Argo CD, Vault, PostgreSQL, Prometheus, Loki, k6 | dev test staging prod |

Only `prod` waits for approval, except in `concourse-kubernetes-vault`, where
staging does too; see [Environments](deployment.md#environments).
`concourse-argocd` releases prod as a canary. The end-to-end test runs every
combination on GitHub's runners except `maven-sonarqube-github`, which needs
a real GitHub repository. `jenkins-complete` is the heaviest: it starts every
tool at once.

Step by step: [getting-started.md](getting-started.md#guide-1-a-ready-made-pipeline).

## What it writes

`init --pipeline` selects the pipeline's tools and writes its settings to the
project's `devops.conf`, together with `PIPELINE=<name>`. From then on the
project is like any other: commit `devops.conf`, and a teammate's `setup`
gets the same tools and settings. Change a setting in `devops.conf`, or answer
everything again with `devops.sh secrets --reconfigure`.

## A pipeline of your own

A ready-made pipeline is a `devops.conf` without the project's name: the
orchestrator, the tools as `--with` takes them, and any setting that is
neither secret nor personal. The first comment line describes it.

```properties
# Jenkins and SonarQube with three environments.
ORCHESTRATOR=jenkins
TOOLS=sonarqube,docker-registry,kubernetes
ENVIRONMENTS=dev test prod
ENV_TEST_APPROVAL=yes
ENV_PROD_APPROVAL=yes
KUBERNETES_REPLICAS=1
```

Keep the file anywhere, for example in a repository your team shares, and
pass its path:

```bash
mvn-devops/devops.sh setup --pipeline ../team/jenkins-k8s.conf
```

A file added to the `pipelines` folder of mvn-devops is listed by
`devops.sh pipelines`.
