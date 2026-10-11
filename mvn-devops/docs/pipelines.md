# Ready-made pipelines

A ready-made pipeline is a tested combination of tools with its settings, in
the [pipelines](../pipelines) folder. Use one when you want working tools
without going through the menu and the questions, or write your own to share a
combination with your team. On Windows, run `mvn-devops\devops.bat` instead of
`mvn-devops/devops.sh`.

## Set up a ready-made pipeline

Step 1. List the ready-made pipelines:

```bash
mvn-devops/devops.sh pipelines
```

Step 2. Set up the one you chose, for example `jenkins-complete`:

```bash
mvn-devops/devops.sh setup --pipeline jenkins-complete
```

Step 3. Answer the questions that cannot be chosen for you:

| Question | Answer |
|---|---|
| GitHub user and token | your GitHub user and a token ([github-setup.md](github-setup.md)) |
| Nexus licence | `yes` to accept it (only when Nexus is part of the pipeline) |

Step 4. Commit `devops.conf`, so a teammate's `setup` gets the same tools and settings.

For a full walk-through, see
[getting-started.md](getting-started.md#guide-1-a-ready-made-pipeline).

## Write a pipeline of your own

A pipeline file of your own is set up the same way, with its path in place of
the name. The steps are in
[getting-started.md](getting-started.md#guide-4-a-ready-made-pipeline-file-of-your-own).

## The pipelines

| Pipeline | Orchestrator | Tools | Environments |
|---|---|---|---|
| `maven-basic` | maven-container | registry, Docker Compose over SSH | prod |
| `maven-complete` | maven-container | every tool of the complete pipelines | dev test staging prod |
| `jenkins-complete` | jenkins | every tool of the complete pipelines | dev test staging prod |
| `concourse-complete` | concourse | every tool of the complete pipelines | dev test staging prod |

`maven-basic` builds and tests the project in a container that checks it out
of GitHub, pushes the image to a registry and runs it with Docker Compose on a
machine (a simulated one in Docker unless `DEPLOY_SERVER_URL` names yours). It
has one environment, `prod`, that needs no approval: every pipeline that passes
is deployed.

The three complete pipelines have every tool that needs no account outside
your machine: SonarQube, Nexus, a registry, Trivy, Syft and Cosign, Argo CD on
Kubernetes with Vault and Sealed Secrets, PostgreSQL, Prometheus, Grafana and
Loki, and k6. They deploy to `dev`, `test`, `staging` and `prod`; only `prod`
waits for approval and is released as a canary. They differ only in the
orchestrator. Every tool runs at once, so give Docker plenty of memory.

For any other combination, pick the tools from the menu (`init`) or write a
pipeline of your own. The end-to-end test runs the three complete pipelines on
GitHub's runners.

## How it works

`setup --pipeline` asks only for your GitHub user and token, and whether you
accept the Nexus licence when Nexus is part of the pipeline. Every password,
token and key is generated for your project, so two projects set up from the
same pipeline share no secrets. The images of the tools are downloaded when
they start, as with any other setup.

`init --pipeline` selects the pipeline's tools and writes its settings to the
project's `devops.conf`, together with `PIPELINE=<name>`. From then on the
project is like any other: commit `devops.conf`, and a teammate's `setup` gets
the same tools and settings. Change a setting in `devops.conf`, or answer
everything again with `mvn-devops/devops.sh secrets --reconfigure`.

A ready-made pipeline is a `devops.conf` without the project's name. A file
added to the `pipelines` folder of mvn-devops is listed by
`mvn-devops/devops.sh pipelines`.

## Next

- [Orchestrators](orchestrators.md): how each orchestrator runs the pipeline and how to approve an environment.
- [Where the tools run](where-tools-run.md): use existing servers or another machine.
- [Deployment](deployment.md): environments, approvals and deploy targets.
