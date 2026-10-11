# Reaching tools on your machine from the internet (ngrok)

GitHub has to call Jenkins for the push webhook (`JENKINS_TRIGGER=webhook`).
When Jenkins runs on your machine or a VM without a public address, ngrok
gives it one. Polling (`poll`, the default) and Concourse need nothing from
outside. On Windows, run `mvn-devops\devops.bat` instead of
`mvn-devops/devops.sh`.

## Before you start

- An account at [ngrok.com](https://ngrok.com).
- A GitHub token with `admin:repo_hook` ([github-setup.md](github-setup.md)).

## Install ngrok

Step 1. Install ngrok for your system (see [Settings](#settings)).

Step 2. Add your auth token from the ngrok dashboard:

```bash
ngrok config add-authtoken <token>
```

## Register the Jenkins webhook

Step 1. Start a tunnel to Jenkins on your `JENKINS_HOST_PORT` (8080 by default):

```bash
ngrok http 8080
```

Step 2. Answer the questions again, with `JENKINS_TRIGGER` set to `webhook` and `JENKINS_PUBLIC_URL` set to the tunnel's URL, e.g. `https://<name>.ngrok-free.app`:

```bash
mvn-devops/devops.sh secrets --reconfigure
```

Step 3. Register `<url>/github-webhook/` on the repository:

```bash
mvn-devops/devops.sh publish
```

## Update the webhook when the URL changes

Step 1. Enter the new URL as `JENKINS_PUBLIC_URL`:

```bash
mvn-devops/devops.sh secrets --reconfigure
```

Step 2. Register the webhook again:

```bash
mvn-devops/devops.sh publish
```

Step 3. Remove the old webhook in the repository under Settings > Webhooks.

## Settings

| System | Install |
|---|---|
| Windows | unzip it into, e.g., `C:\sdk\ngrok` and add that folder to `PATH` |
| Linux | `sudo snap install ngrok`, or unpack the tgz into `/opt/ngrok` and add it to `PATH` |
| macOS | `brew install ngrok` |

Examples of tunnels:

```bash
ngrok http 8080                                   # URL changes on every start (free account)
ngrok http --url=<name>.ngrok-free.app 8080       # reserved domain, stable URL
ngrok start --all                                 # every tunnel in the ngrok config file
```

Several tools at once can be described in the ngrok config file
(`ngrok config edit`) and started with `ngrok start --all`:

```yaml
tunnels:
  jenkins:   { addr: 8080, proto: http }
  sonarqube: { addr: 9000, proto: http }
  nexus:     { addr: 8084, proto: http }
```

## How it works

With a free account the URL changes on every start of ngrok, so the webhook
must be updated each time. A reserved domain keeps the URL stable.

## Next

- [Orchestrators](orchestrators.md): the Jenkins build triggers.
- [GitHub setup](github-setup.md): the token and its scopes.
