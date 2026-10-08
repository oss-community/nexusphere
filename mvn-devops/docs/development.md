# Development

## Testing

```bash
shellcheck devops.sh lib/*.sh modules/*/*/module.sh tests/*.sh packaging/*.sh
tests/smoke.sh                       # no Docker needed: init, secrets, stages, render, devops.conf, upgrade
tests/e2e.sh maven sonarqube,nexus   # real tools in Docker: setup, the whole pipeline, results in the tools
tests/e2e.sh pipeline maven-docker-host            # a ready-made pipeline
E2E_ENVIRONMENTS="dev test staging prod" E2E_APPROVALS="staging prod" \
  tests/e2e.sh maven docker-registry,docker-host   # environments of your own
```

`tests/smoke.sh` runs on Linux, macOS and Windows (Git Bash) in the `ci`
workflow. `tests/e2e.sh` builds [examples/hello-maven](../examples/hello-maven), or
[examples/hello-api](../examples/hello-api) for the deployment modules,
with each orchestrator in the `e2e` workflow, on every push to `main` and once a
week; it serves the project from a local git server, so it needs no GitHub
token. With `jfrog` it checks the setup only, because Artifactory OSS
repositories have to be created in its Quick Setup wizard. Run it locally with
Docker and internet access; `E2E_KEEP=1` leaves the containers running for a
look.

## Releasing mvn-devops

Every change worth telling users goes under `## Unreleased` in
[CHANGELOG.md](../CHANGELOG.md). To release, rename that heading to the new
version (`## 1.1.0`), set the same version in `VERSION`, commit, and push a
tag, or start the release workflow by hand under Actions > release > Run
workflow with the version. The workflow stops when CHANGELOG.md has no section
for the version, and uses that section as the text of the GitHub release.

```bash
git tag v1.1.0 && git push origin v1.1.0
```

The release workflow runs the checks, builds the files with
`packaging/build.sh` (zip, tar.gz, and deb and rpm with
[nfpm](https://nfpm.goreleaser.com)), installs the deb as a check and publishes
everything as a GitHub release. `packaging/build.sh` also works locally and
writes to `dist/`.
