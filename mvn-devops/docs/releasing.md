# Releasing your project

`mvn-devops/devops.sh release` releases the Maven project without
maven-release-plugin, so the pom needs no `<scm>` and no
`<distributionManagement>`. Read this page when you want to publish a release
version to the selected artifact repositories. On Windows, run `devops.sh` as
`mvn-devops\devops.bat`.

## Before you start

- `mvn` and git on your machine; the release runs there, not in the orchestrator.
- A clean working tree: no uncommitted changes to tracked files.
- Git credentials or an SSH key that can push to the repository.
- An artifact repository module selected (see [Modules and stages](modules.md)); without one the release is only tagged.

## Release a version

Step 1. Check what would happen:

```bash
mvn-devops/devops.sh release --dry-run
```

Step 2. Release, for example `1.2.0` followed by `1.2.1-SNAPSHOT`:

```bash
mvn-devops/devops.sh release
```

## Undo a pushed release

Step 1. Delete the tag:

```bash
git push origin :refs/tags/v1.2.0
```

Step 2. Revert the two commits of the release (the release commit and the next development version).

## Settings

Options of `release`:

| Setting | Default | Meaning |
|---|---|---|
| `--version X` | the current version without `-SNAPSHOT` | the release version |
| `--next Y-SNAPSHOT` | the release version with the last number raised, plus `-SNAPSHOT` | the next development version |
| `--dry-run` | off | show what would happen |
| `--no-push` | off | check locally, push yourself |

Examples:

```bash
mvn-devops/devops.sh release --version 2.0.0 --next 2.1.0-SNAPSHOT
mvn-devops/devops.sh release --no-push    # check locally, push yourself
```

## How it works

`release` does four things in order:

- sets the release version in every module (`1.2.0-SNAPSHOT` becomes `1.2.0`),
  commits "Release 1.2.0" and tags `v1.2.0`
- runs the deploy stages of the selected artifact repositories
- sets the next development version (`1.2.1-SNAPSHOT`) and commits it
- pushes the branch and the tag

Nothing is pushed before the last step: if a step fails, the release commit
and the tag are rolled back.

## Next

- [Modules and stages](modules.md): the deploy stages of each artifact repository.
- [Troubleshooting](troubleshooting.md): when something does not work.
