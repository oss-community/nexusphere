# Changelog

Every release of mvn-devops. The section of a version is also the text of its
GitHub release.

## 1.0.0

First release.

### Setup

- A menu of modules per category: GitHub; Maven; SonarQube; Nexus,
  Artifactory OSS and GitHub Packages; GitHub Pages; container images; image
  security; deployment; GitOps; secrets; a database; monitoring; a load test.
- Orchestrators: `maven` (on this machine), `maven-container` (in a container
  on the Docker machine, from a fresh checkout of GitHub), Jenkins
  (configuration as code, no setup wizard) and Concourse.
- `setup` asks the questions, starts the tools in Docker, replaces their
  default passwords, creates tokens and repositories, and installs the
  pipeline. Every tool runs in Docker, on this machine or another one, or is
  an existing server with its own URL; GitHub Enterprise is supported.
- `devops.conf` in the project root holds the selected tools and every answer
  that is neither secret nor personal. Commit it: a teammate's `setup` asks
  only for their own tokens and user names. Passwords and keys stay in
  `.devops/`.
- Four ready-made pipelines in `pipelines/`: `maven-basic` (build, test and
  run the image with Docker Compose on a machine) and `maven-complete`,
  `jenkins-complete` and `concourse-complete` with every tool.
  `setup --pipeline <name or file>` sets one up and generates new passwords,
  tokens and keys for each project.
- No profiles, plugins or `distributionManagement` in the project's pom:
  plugins are called by their coordinates.

### Pipeline

- Container images built with Jib (no Docker or Dockerfile needed) or with
  the project's Dockerfile, pushed to a registry in Docker, an existing one or
  the GitHub Container Registry.
- Image security: Trivy scans the image, Syft writes its SBOM, Cosign signs
  it and checks the signature before an approved environment gets it.
- Deployment environments of the project's own (`ENVIRONMENTS`, e.g.
  `dev test staging prod`), each with its own machine or namespace, port,
  secrets and database, and an approval where `ENV_<NAME>_APPROVAL=yes`. The
  same image goes through them in order.
- Deployment with Docker Compose over SSH, with Helm on Kubernetes (an
  existing cluster or k3s in Docker), or with Argo CD from a GitOps branch,
  the last environment as a canary with Argo Rollouts. Each deployment is
  checked and rolled back when it fails; `rollback` puts the previous image
  back.
- Secrets from Vault, passed to the application as environment variables, a
  Kubernetes Secret or a SealedSecret.
- PostgreSQL per environment, with the Flyway migrations checked against an
  empty database in ci.
- Prometheus, Grafana and Loki watch each environment; k6 load tests one of
  them after each deployment.
- `release` releases the project without maven-release-plugin.

### Tools and packaging

- `doctor` checks the prerequisites; `doctor --fix` repairs CRLF line endings.
- `upgrade` replaces the copy of mvn-devops inside a project with a release,
  checked against `SHA256SUMS`.
- `export-compose` writes the selected tools as one docker-compose.yml.
- Packages: zip, tar.gz, deb and rpm. Runs on Linux, macOS and Windows (Git
  Bash).
- `docs/getting-started.md` has step-by-step guides; `examples/` has sample
  projects.
- Tested on every push: a smoke test on Linux, macOS and Windows, and an
  end-to-end test of the three complete pipelines with the real tools on
  GitHub's runners.
