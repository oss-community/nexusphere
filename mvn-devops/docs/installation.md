# Installation

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
the same way. `mvn-devops --version` prints the installed version.

## Shipping it with the project (zip)

mvn-devops can live inside the project and be committed with it, so everyone
who clones the project gets the same DevOps setup, like the Maven wrapper.

**1. Download and extract** `mvn-devops-<version>.zip` from the
[releases page](https://github.com/oss-community/mvn-devops/releases) into the
project root, and rename the folder to `mvn-devops` so paths stay the same
across upgrades.

Linux, macOS, Git Bash:

```bash
cd my-maven-project
curl -fsSLO https://github.com/oss-community/mvn-devops/releases/download/v1.0.0/mvn-devops-1.0.0.zip
unzip -q mvn-devops-1.0.0.zip && mv mvn-devops-1.0.0 mvn-devops && rm mvn-devops-1.0.0.zip
```

Windows PowerShell (or right-click the zip > Extract All, then rename the folder):

```powershell
cd my-maven-project
Invoke-WebRequest https://github.com/oss-community/mvn-devops/releases/download/v1.0.0/mvn-devops-1.0.0.zip -OutFile mvn-devops.zip
Expand-Archive mvn-devops.zip -DestinationPath . ; Rename-Item mvn-devops-1.0.0 mvn-devops ; Remove-Item mvn-devops.zip
```

The project then looks like this:

```
my-maven-project/
  pom.xml
  mvn-devops/          committed: devops.sh, devops.bat, lib/, modules/, ...
  .devops/             created by setup, never committed (passwords, tokens)
```

**2. Commit it:**

```bash
git add mvn-devops && git commit -m "Add mvn-devops 1.0.0"
```

**3. Use it from the project root:**

```bash
mvn-devops/devops.sh setup        # Windows: mvn-devops\devops.bat setup
mvn-devops/devops.sh run
```

Commit `devops.conf` too. Everyone else clones the project and runs
`mvn-devops/devops.sh setup`: the tools and answers come from `devops.conf`, so
they are only asked for their own passwords, tokens and user names.

**Upgrade** with `mvn-devops/devops.sh upgrade`: it downloads the latest
release, checks it against `SHA256SUMS` and replaces the folder; review the
change with `git status` and commit it. `upgrade --check` only compares
versions, `upgrade --version X` picks a release, and `--version` shows the
version in use.

Notes:

- The folder has no `pom.xml`, so Maven, Sonar and the pipeline ignore it.
- Use the zip or tar.gz, not a `git clone`, which would put a repository
  inside the project's repository.
- On Linux and macOS keep the executable bit when committing (`unzip` and git
  keep it). If `devops.sh` lost it, run
  `git update-index --chmod=+x mvn-devops/devops.sh`.
- Line endings: Bash cannot run scripts with Windows (CRLF) line endings, and
  git on Windows (`core.autocrlf=true`) converts files to CRLF on checkout.
  The zip contains `mvn-devops/.gitattributes`, which keeps every file LF (and
  `devops.bat` CRLF) whatever `core.autocrlf` is, so commit it together with
  the folder. `devops.sh doctor` reports files that are already CRLF and prints
  the command that fixes them. A project that committed mvn-devops before this
  file existed adds it from the new zip, then fixes its checkout once with:

  ```bash
  git add --renormalize mvn-devops && git commit -m "Normalize mvn-devops line endings"
  rm -rf mvn-devops && git checkout -- mvn-devops
  ```
