# Development

This page is for people who change mvn-devops itself: how to run the tests,
how to release a new version of mvn-devops and how to write its
documentation. The commands run from the root of a clone of the mvn-devops
repository.

## Before you start

- A clone of the mvn-devops repository.
- `shellcheck`.
- Docker and internet access for the end-to-end tests.

## Run the tests

Step 1. Check the scripts with shellcheck:

```bash
shellcheck devops.sh lib/*.sh modules/*/*/module.sh tests/*.sh packaging/*.sh
```

Step 2. Run the smoke test, which needs no Docker:

```bash
tests/smoke.sh
```

Step 3. Run an end-to-end test with the real tools in Docker, for example the `maven` orchestrator with SonarQube and Nexus:

```bash
tests/e2e.sh maven sonarqube,nexus
```

More end-to-end runs are listed under [Settings](#settings).

## Release mvn-devops

Step 1. In [CHANGELOG.md](../CHANGELOG.md), rename the `## Unreleased` heading to the new version, e.g. `## 1.1.0`.

Step 2. Set the same version in `VERSION`:

```bash
echo 1.1.0 > VERSION
```

Step 3. Commit both files:

```bash
git commit -am "Release 1.1.0"
```

Step 4. Push the commit:

```bash
git push origin main
```

Step 5. Push the tag, which starts the release workflow:

```bash
git tag v1.1.0 && git push origin v1.1.0
```

## Settings

| Test | What it covers |
|---|---|
| `tests/smoke.sh` | no Docker needed: init, secrets, stages, render, devops.conf, upgrade |
| `tests/e2e.sh <orchestrator> <modules>` | real tools in Docker: setup, the whole pipeline, results in the tools |
| `tests/e2e.sh pipeline <name>` | a ready-made pipeline |

Environment variables of `tests/e2e.sh`:

| Setting | Default | Meaning |
|---|---|---|
| `E2E_ENVIRONMENTS` | empty | deployment environments of your own, e.g. `"dev test staging prod"`; empty keeps the project's defaults |
| `E2E_APPROVALS` | the last environment | the environments that wait for approval, e.g. `"staging prod"` |
| `E2E_EXAMPLE` | chosen by the modules | the example project to build |
| `E2E_KEEP` | `0` | `1` leaves the containers running for a look |

Examples:

```bash
tests/e2e.sh maven sonarqube,nexus    # real tools in Docker
tests/e2e.sh pipeline maven-basic     # a ready-made pipeline
E2E_ENVIRONMENTS="dev test staging prod" E2E_APPROVALS="staging prod" \
  tests/e2e.sh maven docker-registry,docker-host   # environments of your own
```

## How it works

### Tests

`tests/smoke.sh` runs on Linux, macOS and Windows (Git Bash) in the `ci`
workflow, on every push to `main` and on pull requests, after shellcheck.

`tests/e2e.sh` builds [examples/hello-maven](../examples/hello-maven),
[examples/hello-api](../examples/hello-api) when an image is built, or
[examples/hello-data](../examples/hello-data) with a database. It serves the
project from a local git server, so it needs no GitHub token. With `jfrog` it
checks the setup only, because Artifactory OSS repositories have to be created
in its Quick Setup wizard. The `e2e` workflow runs it every night and by hand
(Actions > e2e > Run workflow) with the maven-complete, jenkins-complete and
concourse-complete pipelines.

### Releases

Instead of pushing a tag, you can start the release workflow by hand under
Actions > release > Run workflow with the version. The workflow stops when
CHANGELOG.md has no section for the version, and uses that section as the text
of the GitHub release. Every change worth telling users goes under
`## Unreleased` in CHANGELOG.md until then.

The release workflow runs the checks, builds the files with
`packaging/build.sh` (zip, tar.gz, and deb and rpm with
[nfpm](https://nfpm.goreleaser.com)), installs the deb as a check and publishes
everything as a GitHub release. `packaging/build.sh` also works locally and
writes to `dist/`.

## Writing documentation

Every page in README.md and docs/ has the same structure:

```
# <Title: a noun phrase>
<One short paragraph: what the page covers and when the reader needs it.>
## Before you start      only with prerequisites; bullets with links
## <Task as a verb phrase>   one section per task, made of "Step N." paragraphs
## Settings              reference tables, after the tasks
## How it works          optional explanation, after tasks and settings
## Next                  last section: 1 to 3 links, "- [Page](file.md): what it is for"
```

A pure reference page may have no task section, but still opens with the
intro paragraph and ends with `## Next`.

1. **Steps.** A procedure is a sequence of plain-text "Step N." paragraphs
   (not headings, not markdown lists, no bold pseudo-headings), each one
   imperative sentence with at most one code block holding one command (two
   only when they must run together, joined with `&&`). No explanations
   between steps; they go in "How it works".
2. **Commands** run from the project root as `mvn-devops/devops.sh <command>`
   (not `devops.sh` or `./devops.sh`). Pages with steps say once that on
   Windows it is `mvn-devops\devops.bat`.
3. **Code blocks in steps** have no trailing `# comments`; reference examples
   may.
4. **Tables:** `| Setting | Default | Meaning |`, `| Stage | Phase | What happens |`
   and `| Question | Answer |`. Write `empty` for an empty default.
5. **Words:** short sentences, present tense, "you", English only. Name things
   exactly as the code does.
6. **Facts do not change.** Check settings, defaults, commands and options
   against the code; do not invent options or drop information, move it.
7. **Links** keep working. Grep for `<file>.md#` before renaming a heading;
   link between docs as `[Title](file.md)`.
8. **No duplicates:** a procedure lives on one page; others link to it.
9. Ready-made pipelines are exactly maven-basic, maven-complete,
   jenkins-complete and concourse-complete; orchestrators are maven,
   maven-container, jenkins and concourse.

## Next

- [Writing a module](module-guide.md): add a tool of your own.
- [Design](design.md): how mvn-devops is built.
