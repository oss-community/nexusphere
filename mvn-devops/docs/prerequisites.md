# Prerequisites

This page has the install commands for the tools mvn-devops needs, per
system: Debian and Ubuntu, Fedora and RHEL, macOS and Windows. Use it before
your first `setup`, or when `doctor` reports a missing tool. The list of tools
and what each is needed for is in [Required tools](#required-tools). On
Windows, use `mvn-devops\devops.bat` instead of `mvn-devops/devops.sh`.

## Install on Debian, Ubuntu

Step 1. Update the package lists:

```bash
sudo apt-get update
```

Step 2. Install the tools, Java and Maven:

```bash
sudo apt-get install -y bash curl jq git openssh-client openjdk-21-jdk maven
```

Step 3. Install Docker Engine with the compose plugin:

```bash
curl -fsSL https://get.docker.com | sudo sh
```

Step 4. Allow your user to run Docker:

```bash
sudo usermod -aG docker "$USER"
```

Step 5. Log out and in again so the group change applies.

Step 6. Check that Maven is 3.9 or newer, and if not, follow [Install Maven by hand](#install-maven-by-hand-any-linux-macos):

```bash
mvn -version
```

## Install on Fedora, RHEL, Rocky

Step 1. Install the tools, Java and Maven:

```bash
sudo dnf install -y bash curl jq git openssh-clients java-21-openjdk-devel maven
```

Step 2. Install the dnf plugins:

```bash
sudo dnf -y install dnf-plugins-core
```

Step 3. Add the Docker repository:

```bash
sudo dnf config-manager --add-repo https://download.docker.com/linux/fedora/docker-ce.repo
```

Step 4. Install Docker Engine with the compose plugin:

```bash
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
```

Step 5. Start Docker and allow your user to run it:

```bash
sudo systemctl enable --now docker && sudo usermod -aG docker "$USER"
```

Step 6. Log out and in again so the group change applies.

Step 7. Check that Maven is 3.9 or newer, and if not, follow [Install Maven by hand](#install-maven-by-hand-any-linux-macos):

```bash
mvn -version
```

## Install on macOS

Step 1. Install Bash, jq, git, Java and Maven with Homebrew:

```bash
brew install bash jq git openjdk@21 maven
```

Step 2. Install Docker Desktop:

```bash
brew install --cask docker
```

Step 3. Start Docker Desktop once.

## Install on Windows

Step 1. Install [Git for Windows](https://git-scm.com/download/win), which brings Git Bash, curl and ssh-keygen.

Step 2. Install jq:

```bat
winget install jqlang.jq
```

Step 3. Install [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/) with the WSL 2 backend.

Step 4. Install Java 21:

```bat
winget install EclipseAdoptium.Temurin.21.JDK
```

Step 5. Download the Maven binary zip from [maven.apache.org](https://maven.apache.org/download.cgi) and unpack it to e.g. `C:\sdk\maven`.

Step 6. Set `MAVEN_HOME` in a console run as administrator:

```bat
setx /M MAVEN_HOME C:\sdk\maven
```

Step 7. Add Maven to `PATH` in the same console:

```bat
setx /M PATH "%PATH%;C:\sdk\maven\bin"
```

Step 8. Open a new console so `PATH` is reloaded.

## Install Maven by hand (any Linux, macOS)

Step 1. Download Maven:

```bash
curl -fsSLO https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz
```

Step 2. Unpack it into `/opt/maven`:

```bash
sudo mkdir -p /opt/maven && sudo tar -xzf apache-maven-3.9.11-bin.tar.gz -C /opt/maven --strip-components=1
```

Step 3. Add it to `PATH`:

```bash
echo 'export PATH=/opt/maven/bin:$PATH' >> ~/.bashrc
```

## Check the installation

Step 1. Print the versions of the tools:

```bash
java -version && mvn -version && git --version && docker compose version && jq --version
```

Step 2. Let mvn-devops check all of them:

```bash
mvn-devops/devops.sh doctor
```

## Required tools

| Tool | Needed for |
|---|---|
| Bash 4+, curl, jq, git, ssh-keygen | always |
| Docker with the compose plugin | tools that run in Docker |
| Java 21 and Maven 3.9 | the `maven` orchestrator and `release` (Jenkins and Concourse bring their own) |

## How it works

`mvn-devops/devops.sh doctor` (or `mvn-devops doctor` with a package install)
checks all of the tools above.

Docker's own install guides are at
[docs.docker.com/engine/install/ubuntu](https://docs.docker.com/engine/install/ubuntu/)
and [docs.docker.com/engine/install/fedora](https://docs.docker.com/engine/install/fedora/)
(or `/rhel/`). The Maven packages of Debian, Ubuntu and RHEL are often older
than 3.9, which is why each Linux section checks `mvn -version`.

macOS ships Bash 3.2; Homebrew's Bash 4+ is used through `#!/usr/bin/env bash`
when `/opt/homebrew/bin` (or `/usr/local/bin`) comes first in `PATH`.

On Windows, `devops.bat` finds Git Bash by itself. Instead of `winget`, you
can download `jq-windows-amd64.exe` from
[jqlang.github.io/jq](https://jqlang.github.io/jq/download/), rename it to
`jq.exe` and put it in a folder on `PATH`.

## Next

- [GitHub setup](github-setup.md): the token and SSH key `setup` needs
- [Installation](installation.md): add mvn-devops to your project
