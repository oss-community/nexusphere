# GitHub tokens and SSH keys

This page shows how to create the GitHub tokens that `secrets` asks for, and
the SSH key that is only needed when the `maven` orchestrator publishes the
site from your machine. Do this once before `setup`. On Windows, use
`mvn-devops\devops.bat` instead of `mvn-devops/devops.sh`.

## Create a token

Step 1. Open GitHub > Settings > Developer settings > Personal access tokens > Tokens (classic) > Generate new token ([direct link](https://github.com/settings/tokens/new)); on GitHub Enterprise it is the same menu on your server.

Step 2. Select the scopes the token needs, as listed in [Token scopes](#token-scopes).

Step 3. Generate the token and copy it right away, because GitHub shows it only once.

Step 4. Enter it when `setup` or `secrets` asks for `GITHUB_TOKEN` (or `GITHUB_PACKAGE_TOKEN` for the packages token):

```bash
mvn-devops/devops.sh secrets
```

## Change a token later

Step 1. Answer the questions again and enter the new token:

```bash
mvn-devops/devops.sh secrets --reconfigure
```

Step 2. Publish the pipeline so it gets the new value:

```bash
mvn-devops/devops.sh publish
```

## Set up an SSH key for site publishing

Only needed with the `maven` orchestrator. On Windows, run these steps in Git
Bash.

Step 1. Create a key and accept the default file:

```bash
ssh-keygen -t ed25519 -C "my-laptop"
```

Step 2. Add the key to the SSH agent:

```bash
eval "$(ssh-agent -s)" && ssh-add ~/.ssh/id_ed25519
```

Step 3. Trust the host key of GitHub:

```bash
ssh-keyscan github.com >> ~/.ssh/known_hosts
```

Step 4. Print the public key:

```bash
cat ~/.ssh/id_ed25519.pub
```

Step 5. Add it at [github.com/settings/ssh/new](https://github.com/settings/ssh/new), or with the GitHub CLI:

```bash
gh ssh-key add ~/.ssh/id_ed25519.pub
```

Step 6. Check that GitHub accepts the key; it answers "Hi <user>! You've successfully authenticated":

```bash
ssh -T git@github.com
```

## Token scopes

Create classic tokens with these scopes:

| Token | Asked as | Scopes | Used for |
|---|---|---|---|
| Repository token | `GITHUB_TOKEN` | `repo` | Jenkins and Concourse clone the repository; `configure` checks the site branch and registers the deploy key |
| | | `admin:repo_hook` | only with `JENKINS_TRIGGER=webhook`: `configure` registers the push webhook |
| Packages token (optional) | `GITHUB_PACKAGE_TOKEN` | `write:packages`, `read:packages` | the `deploy-github` stage. Empty: the repository token is used, so give it these scopes too |
| | | `delete:packages` | only if you want to delete package versions later |
| | | `write:packages` on `GITHUB_TOKEN` | the `image` stage of the `github-container` module pushes to ghcr.io with the repository token |

With a public repository and no private dependencies, `public_repo` is enough
instead of `repo`.

**Fine-grained tokens** work for the repository token: select the repository
and give it *Contents: Read and write*, *Administration: Read and write*
(deploy keys) and, for the webhook, *Webhooks: Read and write*. GitHub Packages
does not accept fine-grained tokens yet, so the packages token must be a
classic one.

## How it works

`publish-site` pushes the site with `git@github.com:<owner>/<repo>.git`. With
Jenkins or Concourse a deploy key is generated and registered for you. With the
`maven` orchestrator your own key is used, which is why you set it up once.
`mvn-devops/devops.sh doctor` runs the `ssh -T` check for the project's GitHub
host.

## Next

- [Installation](installation.md): add mvn-devops to your project
- [Getting started](getting-started.md): step-by-step guides for your first pipeline
- [Project site](github-pages.md): the Maven site on GitHub Pages
