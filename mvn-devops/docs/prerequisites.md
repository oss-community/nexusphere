# Installing the prerequisites

| Tool | Needed for |
|---|---|
| Bash 4+, curl, jq, git, ssh-keygen | always |
| Docker with the compose plugin | tools that run in Docker |
| Java 17 and Maven 3.9 | the `maven` orchestrator and `release` (Jenkins and Concourse bring their own) |

`devops.sh doctor` (or `mvn-devops doctor`) checks all of them.

## Debian, Ubuntu

```bash
sudo apt-get update
sudo apt-get install -y bash curl jq git openssh-client openjdk-17-jdk maven
# Docker Engine with the compose plugin: https://docs.docker.com/engine/install/ubuntu/
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"     # log out and in again
```

## Fedora, RHEL, Rocky

```bash
sudo dnf install -y bash curl jq git openssh-clients java-17-openjdk-devel maven
# Docker Engine: https://docs.docker.com/engine/install/fedora/ (or /rhel/)
sudo dnf -y install dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/fedora/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker && sudo usermod -aG docker "$USER"
```

The Maven package of older RHEL releases can be older than 3.9; then install
Maven by hand as described for macOS/Linux below.

## macOS

```bash
brew install bash jq git openjdk@17 maven
brew install --cask docker          # Docker Desktop, start it once
```

macOS ships Bash 3.2; Homebrew's Bash 4+ is used through `#!/usr/bin/env bash`
when `/opt/homebrew/bin` (or `/usr/local/bin`) comes first in `PATH`.

## Windows

1. [Git for Windows](https://git-scm.com/download/win): brings Git Bash, curl
   and ssh-keygen. `devops.bat` finds it.
2. jq: `winget install jqlang.jq`, or download `jq-windows-amd64.exe` from
   [jqlang.github.io/jq](https://jqlang.github.io/jq/download/), rename it to
   `jq.exe` and put it in a folder on `PATH`.
3. [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/) with the WSL 2 backend.
4. Java 17: `winget install EclipseAdoptium.Temurin.17.JDK`.
5. Maven: download the binary zip from [maven.apache.org](https://maven.apache.org/download.cgi),
   unpack it to e.g. `C:\sdk\maven`, then in a console run as administrator:
   ```bat
   setx /M MAVEN_HOME C:\sdk\maven
   setx /M PATH "%PATH%;C:\sdk\maven\bin"
   ```

Open a new console afterwards so `PATH` is reloaded.

## Manual Maven install (any Linux, macOS)

```bash
curl -fsSLO https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz
sudo mkdir -p /opt/maven && sudo tar -xzf apache-maven-3.9.11-bin.tar.gz -C /opt/maven --strip-components=1
echo 'export PATH=/opt/maven/bin:$PATH' >> ~/.bashrc
```

## Check

```bash
java -version && mvn -version && git --version && docker compose version && jq --version
```
