# GitHub tokens and SSH keys

Do this once before `setup`. `secrets` asks for the tokens; the SSH key is
only needed when the `maven` orchestrator publishes the site from your machine.

## Tokens

Create classic tokens under GitHub > Settings > Developer settings >
Personal access tokens > Tokens (classic) > Generate new token
([direct link](https://github.com/settings/tokens/new)). On GitHub Enterprise
it is the same menu on your server. Copy each token right away; GitHub shows it
only once.

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

Change a token later with `devops.sh secrets --reconfigure`, then
`devops.sh publish` so the pipeline gets the new value.

## SSH key (site publishing with the `maven` orchestrator)

`publish-site` pushes the site with `git@github.com:<owner>/<repo>.git`. With
Jenkins or Concourse a deploy key is generated and registered for you. With the
`maven` orchestrator your own key is used, so set it up once:

```bash
ssh-keygen -t ed25519 -C "my-laptop"                    # accept the default file
eval "$(ssh-agent -s)" && ssh-add ~/.ssh/id_ed25519     # Windows: run in Git Bash
ssh-keyscan github.com >> ~/.ssh/known_hosts
cat ~/.ssh/id_ed25519.pub                               # add it at github.com/settings/ssh/new
ssh -T git@github.com                                   # "Hi <user>! You've successfully authenticated"
```

Or add it with the GitHub CLI: `gh ssh-key add ~/.ssh/id_ed25519.pub`.
`devops.sh doctor` runs the `ssh -T` check for the project's GitHub host.
