# <p align="center">Local Environment Setup</p>

<p align="center">One-time steps that prepare a developer machine to build Nexusphere and run mvn-devops.</p>

## <p align="center">Table of Content</p>

* [Tools](#tools)
* [Install the Tools](#install-the-tools)
* [Get the Code](#get-the-code)
* [Windows Shell](#windows-shell)
* [IntelliJ IDEA Terminal](#intellij-idea-terminal)
* [Check the Machine](#check-the-machine)
* [Next Steps](#next-steps)

## Tools

| Tool       | Version | Check                    |
|------------|---------|--------------------------|
| Java (JDK) | 21      | `java -version`          |
| Maven      | 3.9     | `mvn -version`           |
| Node.js    | 22      | `node --version`         |
| Docker     | any     | `docker compose version` |
| Git        | any     | `git --version`          |
| curl       | any     | `curl --version`         |
| jq         | any     | `jq --version`           |
| OpenSSL    | any     | `openssl version`        |
| ssh-keygen | any     | `ssh -V`                 |

<p style="text-align: justify;">

Node.js is needed only for the web UI and the TypeScript SDK. On Windows, Git for Windows brings Bash, curl, OpenSSL
and ssh-keygen.

</p>

## Install the Tools

Step 1. Install the tools for your system with the commands in
[mvn-devops/docs/prerequisites.md](../mvn-devops/docs/prerequisites.md), and Node.js 22 from
[nodejs.org](https://nodejs.org).

Step 2. Open a new terminal and check the versions; Java must be 21, Maven 3.9 and Node.js 22 or later:

```shell
java -version
mvn -version
node --version
docker compose version
git --version
jq --version
openssl version
```

## Get the Code

Step 1. On Windows, keep LF line endings in the scripts; skip this step on Linux and macOS:

```shell
git config --global core.autocrlf input
```

Step 2. Clone the repository and enter it:

```shell
git clone https://github.com/oss-community/nexusphere.git
cd nexusphere
```

Step 3. Check the line endings; it must end with `All good`, and a `line endings` line must be ✔:

```shell
mvn-devops/devops.sh doctor
```

Step 4. Only if the doctor reports files with CRLF line endings, write them again from Git and run the doctor again:

```shell
rm -rf mvn-devops
git checkout HEAD -- mvn-devops
```

## Windows Shell

<p style="text-align: justify;">

mvn-devops runs in Bash. On Windows use Git Bash, which needs no settings and is what `mvn-devops\devops.bat` starts,
or Cygwin; not WSL and not PowerShell. Cygwin must use its own `bash`: when the Windows folders come first in `PATH`,
`bash` resolves to `C:\Windows\System32\bash.exe`, which is WSL, so `java.exe` and `jq.exe` are not found and CRLF
files fail with `set: pipefail: invalid option name`. If `setup` fails with `couldn't find env file` in Cygwin, run
the Docker commands from Git Bash.

</p>

Step 1. In Cygwin, check which `bash` runs; the answer must be `/usr/bin/bash`:

```shell
command -v bash
```

Step 2. Only if the answer is `/cygdrive/c/Windows/system32/bash`, put the Cygwin folders first in `~/.bashrc`, open a
new terminal and run step 1 again; `echo "$HOME"` shows which folder `~` is:

```shell
echo 'export PATH="/usr/local/bin:/usr/bin:$PATH"' >> ~/.bashrc
```

## IntelliJ IDEA Terminal

Step 1. Open Settings > Tools > Terminal and set Shell path, and the environment variable where the table has one:

| Shell    | Shell path                         | Environment variables |
|----------|------------------------------------|-----------------------|
| Git Bash | `"<git>\bin\bash.exe" --login -i`  |                       |
| Cygwin   | `<cygwin>\bin\bash.exe`            |                       |
| Cygwin   | `<cygwin>\bin\bash.exe --login -i` | `CHERE_INVOKING=1`    |

<p style="text-align: justify;">

`<git>` is the Git for Windows folder, for example `C:\Program Files\Git`, and `<cygwin>` is the Cygwin folder, for
example `C:\cygwin64`. A Cygwin login shell moves to the home folder unless `CHERE_INVOKING=1` is set, and without
`--login` Cygwin needs the `PATH` line from [Windows Shell](#windows-shell).

</p>

Step 2. Open a new terminal in IntelliJ IDEA; it must start in the project folder, and `command -v bash` must not
answer a Windows path:

```shell
pwd
command -v bash
```

<p style="text-align: justify;">

Pipeline variables for IntelliJ IDEA run configurations, Checkstyle and coverage are in
[mvn-devops/docs/ide.md](../mvn-devops/docs/ide.md).

</p>

## Check the Machine

Step 1. Check the prerequisites of mvn-devops; it must end with `All good`, and the SSH key line may stay a warning
because the key is needed only to publish the site:

```shell
mvn-devops/devops.sh doctor
```

Step 2. Build the project without tests; it must end with `BUILD SUCCESS`:

```shell
mvn -B package -DskipTests=true
```

Step 3. Run the unit tests; it must end with `BUILD SUCCESS`:

```shell
mvn -B test
```

## Next Steps

<p style="text-align: justify;">

Start the ledger and its services with [Quick Start](../README.md#quick-start), learn the ledger with the
[Ledger Guide](ledger-guide.md), and build, test and deploy it to four environments with
[DevOps Step by Step](devops-guide.md). The GitHub token and SSH key that mvn-devops uses are described in
[mvn-devops/docs/github-setup.md](../mvn-devops/docs/github-setup.md).

</p>

##

**<p align="center">[Top](#local-environment-setup)</p>**
