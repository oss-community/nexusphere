# <p align="center">Local Environment Setup</p>

<p align="center">One-time settings on a developer machine for building Nexusphere and running mvn-devops.</p>

## <p align="center">Table of Content</p>

* [Tools](#tools)
* [Git Line Endings](#git-line-endings)
* [Windows Shell](#windows-shell)
* [IntelliJ IDEA Terminal](#intellij-idea-terminal)
* [GitHub Access](#github-access)
* [Check](#check)

## Tools

| Tool       | Version | Check                    |
|------------|---------|--------------------------|
| Java (JDK) | 21      | `java -version`          |
| Maven      | 3.9     | `mvn -version`           |
| Docker     | any     | `docker compose version` |
| Git        | any     | `git --version`          |
| curl       | any     | `curl --version`         |
| jq         | any     | `jq --version`           |

Install steps per operating system are in [mvn-devops/docs/prerequisites.md](../mvn-devops/docs/prerequisites.md).

## Git Line Endings

The Bash scripts must keep LF line endings. The repository enforces this with `.gitattributes`, but a working tree
cloned with `core.autocrlf=true` before that may still hold CRLF files. On Windows, prefer:

```shell
git config --global core.autocrlf input
```

If `devops.sh doctor` reports files with CRLF line endings, write them again from Git at the project root:

```shell
rm -rf mvn-devops
git checkout HEAD -- mvn-devops
```

## Windows Shell

mvn-devops runs in Bash. On Windows use Git Bash or Cygwin, not WSL and not PowerShell.

### Git Bash

Works without extra settings. `mvn-devops\devops.bat` also starts the scripts with Git Bash.

### Cygwin

Cygwin must use its own `bash`. When the Windows folders come first in `PATH`, `bash` resolves to
`C:\Windows\System32\bash.exe`, which is WSL, and the scripts run inside WSL: `java.exe` and `jq.exe` are not found
there, and CRLF files fail with `set: pipefail: invalid option name`.

Check it:

```shell
command -v bash
```

The answer must be `/usr/bin/bash`. If it is `/cygdrive/c/Windows/system32/bash`, put the Cygwin folders first in
`~/.bashrc`. `~` is the Cygwin `$HOME`, which can be the Windows home such as `C:\Users\<user>`; `echo "$HOME"` shows
it.

```shell
echo 'export PATH="/usr/local/bin:/usr/bin:$PATH"' >> ~/.bashrc
```

Open a new terminal and run `command -v bash` again.

The first build of mvn-devops 1.0.0 passed Cygwin paths such as `/cygdrive/c/...` to `docker.exe`, which reads them as
`C:\cygdrive\c\...`, so `setup` failed with `couldn't find env file`. The release rebuilt on 2026-10-08 converts these
paths with `cygpath`. If `setup` still fails with that message, run the Docker commands from Git Bash.

## IntelliJ IDEA Terminal

Settings > Tools > Terminal > Shell path:

| Shell    | Shell path                                    | Environment variables |
|----------|-----------------------------------------------|-----------------------|
| Git Bash | `"<git>\bin\bash.exe" --login -i`             |                       |
| Cygwin   | `<cygwin>\bin\bash.exe`                       |                       |
| Cygwin   | `<cygwin>\bin\bash.exe --login -i`            | `CHERE_INVOKING=1`    |

`<git>` is the Git for Windows folder, for example `C:\Program Files\Git`, and `<cygwin>` is the Cygwin folder, for
example `C:\cygwin64`. A Cygwin login shell moves to the home folder unless `CHERE_INVOKING=1` is set, so the terminal
would not start in the project folder. Without `--login`, Cygwin needs the `PATH` line from [Cygwin](#cygwin).

Pipeline variables for IntelliJ IDEA run configurations, Checkstyle and coverage are described in
[mvn-devops/docs/ide.md](../mvn-devops/docs/ide.md).

## GitHub Access

Tokens and the SSH key used by the pipeline are described in
[mvn-devops/docs/github-setup.md](../mvn-devops/docs/github-setup.md). With the `maven` orchestrator a classic token
with `repo`, `write:packages` and `read:packages` is enough; the SSH key is needed only to publish the site.

## Check

```shell
mvn-devops/devops.sh doctor
```

Every line must be ✔. The SSH key line may stay a warning when the site is not published from this machine.

##

**<p align="center">[Top](#local-environment-setup)</p>**
