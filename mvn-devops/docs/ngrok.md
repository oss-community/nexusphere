# Reaching tools on your machine from the internet (ngrok)

Only needed when GitHub has to call a tool that runs on your machine or a VM
without a public address, which is the Jenkins push webhook
(`JENKINS_TRIGGER=webhook`). Polling (`poll`, the default) and Concourse need
nothing from outside.

1. Create an account at [ngrok.com](https://ngrok.com) and install ngrok:
   - Windows: unzip it into, e.g., `C:\sdk\ngrok` and add that folder to `PATH`.
   - Linux: `sudo snap install ngrok`, or unpack the tgz into `/opt/ngrok` and add it to `PATH`.
   - macOS: `brew install ngrok`.
2. Add your auth token from the ngrok dashboard:
   ```bash
   ngrok config add-authtoken <token>
   ```
3. Start a tunnel to Jenkins (use your `JENKINS_HOST_PORT`, 8080 by default):
   ```bash
   ngrok http 8080
   ```
   With a free account the URL changes on every start; a reserved domain
   (`ngrok http --url=<name>.ngrok-free.app 8080`) keeps it stable.
4. Give that URL to mvn-devops and register the webhook:
   ```bash
   devops.sh secrets --reconfigure     # JENKINS_TRIGGER=webhook, JENKINS_PUBLIC_URL=https://<name>.ngrok-free.app
   devops.sh publish                   # registers <url>/github-webhook/ on the repository
   ```
   The GitHub token needs `admin:repo_hook` for this ([github-setup.md](github-setup.md)).

Several tools at once can be described in the ngrok config file
(`ngrok config edit`) and started with `ngrok start --all`:

```yaml
tunnels:
  jenkins:   { addr: 8080, proto: http }
  sonarqube: { addr: 9000, proto: http }
  nexus:     { addr: 8084, proto: http }
```

When the URL changes, run `secrets --reconfigure` and `publish` again; the old
webhook can be removed under Settings > Webhooks.
