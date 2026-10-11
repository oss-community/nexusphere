# Installation

This page shows how to add mvn-devops to a Maven project so it is committed
with it, how to upgrade that copy, and how to install it on a machine from a
package instead. Read it before your first `setup`. Steps run from the
project root; on Windows, use `mvn-devops\devops.bat` instead of
`mvn-devops/devops.sh`.

## Before you start

- The prerequisites installed: [Prerequisites](prerequisites.md).
- A terminal open in the root of your Maven project (the folder with `pom.xml`).

## Shipping it with the project (zip)

mvn-devops can live inside the project and be committed with it, so everyone
who clones the project gets the same DevOps setup, like the Maven wrapper.
These steps are for Linux, macOS and Git Bash; on Windows PowerShell, follow
[Add the zip with Windows PowerShell](#add-the-zip-with-windows-powershell)
instead of steps 1 and 2. A checkout with Windows (CRLF) line endings is
repaired with [Repair the line endings of a checkout](#repair-the-line-endings-of-a-checkout).

Step 1. Download the release zip into the project root:

```bash
curl -fsSLO https://github.com/oss-community/mvn-devops/releases/download/v1.0.0/mvn-devops-1.0.0.zip
```

Step 2. Extract it and rename the folder to `mvn-devops`:

```bash
unzip -q mvn-devops-1.0.0.zip && mv mvn-devops-1.0.0 mvn-devops && rm mvn-devops-1.0.0.zip
```

Step 3. Commit the folder:

```bash
git add mvn-devops && git commit -m "Add mvn-devops 1.0.0"
```

Step 4. Set up the tools and the pipeline:

```bash
mvn-devops/devops.sh setup
```

Step 5. Run the pipeline:

```bash
mvn-devops/devops.sh run
```

Step 6. Commit the `devops.conf` that `setup` wrote:

```bash
git add devops.conf && git commit -m "Add devops.conf"
```

## Add the zip with Windows PowerShell

You can also right-click the zip > Extract All, then rename the folder to
`mvn-devops`. Continue with step 3 of
[Shipping it with the project (zip)](#shipping-it-with-the-project-zip)
afterwards.

Step 1. Download the release zip into the project root:

```powershell
Invoke-WebRequest https://github.com/oss-community/mvn-devops/releases/download/v1.0.0/mvn-devops-1.0.0.zip -OutFile mvn-devops.zip
```

Step 2. Extract it:

```powershell
Expand-Archive mvn-devops.zip -DestinationPath .
```

Step 3. Rename the folder to `mvn-devops`:

```powershell
Rename-Item mvn-devops-1.0.0 mvn-devops
```

Step 4. Remove the zip:

```powershell
Remove-Item mvn-devops.zip
```

## Upgrade the copy in the project

Step 1. Compare the version in use with the latest release:

```bash
mvn-devops/devops.sh upgrade --check
```

Step 2. Download the latest release and replace the folder with it:

```bash
mvn-devops/devops.sh upgrade
```

Step 3. Review the change:

```bash
git status
```

Step 4. Commit it:

```bash
git add -A mvn-devops && git commit -m "Upgrade mvn-devops to <version>"
```

## Repair the line endings of a checkout

For a project that committed mvn-devops before `mvn-devops/.gitattributes`
existed and now gets CRLF errors.

Step 1. List the files with CRLF line endings:

```bash
mvn-devops/devops.sh doctor
```

Step 2. Copy `mvn-devops/.gitattributes` from the new zip into the `mvn-devops` folder of the project.

Step 3. Renormalize the folder in git:

```bash
git add --renormalize mvn-devops && git commit -m "Normalize mvn-devops line endings"
```

Step 4. Check the folder out again with the fixed line endings:

```bash
rm -rf mvn-devops && git checkout -- mvn-devops
```

## Install it on a machine

Use this instead of the zip in the project when you want one installation for
every project.

Step 1. Download the file for your system from the [releases page](https://github.com/oss-community/mvn-devops/releases), as listed in [Release files](#release-files).

Step 2. Install or unpack it as the table in [Release files](#release-files) says.

Step 3. Check the installed version:

```bash
mvn-devops --version
```

## Release files

Every release on the [releases page](https://github.com/oss-community/mvn-devops/releases) has:

| File | For |
|---|---|
| `mvn-devops_<version>_all.deb` | Debian, Ubuntu: `sudo apt install ./mvn-devops_<version>_all.deb` |
| `mvn-devops-<version>-1.noarch.rpm` | Fedora, RHEL, Rocky: `sudo dnf install ./mvn-devops-<version>-1.noarch.rpm` |
| `mvn-devops-<version>.zip` | Windows: unzip, add the folder to `PATH`, run `devops.bat` (needs Git for Windows) |
| `mvn-devops-<version>.tar.gz` | macOS or any Linux: unpack and add the folder to `PATH` |
| `SHA256SUMS` | checksums of the files above |

The Linux packages install into `/usr/share/mvn-devops` and add the command
`mvn-devops`. With the zip or tar.gz the command is `devops.bat` or
`devops.sh` in the unpacked folder. A `git clone` of this repository works
the same way. `mvn-devops --version` (or `devops.sh --version`) prints the
installed version.

Upgrade options:

| Option | Meaning |
|---|---|
| `upgrade` | download the latest release, check it against `SHA256SUMS` and replace the folder |
| `upgrade --check` | only compare the version in use with the release |
| `upgrade --version X` | pick a release instead of the latest |
| `--version` | show the version in use, e.g. `mvn-devops/devops.sh --version` |

## How it works

With the zip in the project, the project looks like this:

```
my-maven-project/
  pom.xml
  mvn-devops/          committed: devops.sh, devops.bat, lib/, modules/, ...
  .devops/             created by setup, never committed (passwords, tokens)
```

The folder is renamed to `mvn-devops` so paths stay the same across upgrades.
It has no `pom.xml`, so Maven, Sonar and the pipeline ignore it. Use the zip
or tar.gz, not a `git clone`, which would put a repository inside the
project's repository.

`devops.conf` is committed too. Everyone else clones the project and runs
`mvn-devops/devops.sh setup`: the tools and answers come from `devops.conf`,
so they are only asked for their own passwords, tokens and user names.

`upgrade` replaces only a copy that ships inside a project. A package install
is upgraded by installing the new package, and a `git clone` with `git pull`.

## Troubleshooting notes

- **Executable bit.** On Linux and macOS keep the executable bit when
  committing (`unzip` and git keep it). If `devops.sh` lost it, run
  `git update-index --chmod=+x mvn-devops/devops.sh`.
- **Line endings.** Bash cannot run scripts with Windows (CRLF) line endings,
  and git on Windows (`core.autocrlf=true`) converts files to CRLF on
  checkout. The zip contains `mvn-devops/.gitattributes`, which keeps every
  file LF (and `devops.bat` CRLF) whatever `core.autocrlf` is, so commit it
  together with the folder. `mvn-devops/devops.sh doctor` reports files that
  are already CRLF and prints the command that fixes them; `doctor --fix`
  converts them to LF. A project that committed mvn-devops before this file
  existed follows [Repair the line endings of a checkout](#repair-the-line-endings-of-a-checkout).

## Next

- [Prerequisites](prerequisites.md): install commands for Windows, Linux and macOS
- [GitHub setup](github-setup.md): the token `setup` asks for
- [Getting started](getting-started.md): step-by-step guides for your first pipeline
