# Project site on GitHub Pages

The `github-pages` module builds the Maven site and pushes it to a `site`
branch, which GitHub Pages serves. Read this page when you select the module:
two things have to be done once by hand, and then the pipeline publishes the
site. On Windows, run `devops.sh` as `mvn-devops\devops.bat`.

## Before you start

- The `github-pages` module is selected (see [Modules and stages](modules.md)).
- Pushing the site uses SSH; the key is described in [GitHub tokens and SSH keys](github-setup.md).

## Create the site branch

The `site` branch is an orphan branch that only holds the published site.
Run these steps in your project repository.

Step 1. Create the orphan branch:

```bash
git checkout --orphan site
```

Step 2. Remove the project files from the branch's index:

```bash
git rm -rf --cached . > /dev/null
```

Step 3. Write a placeholder page:

```bash
echo "site" > index.html
```

Step 4. Commit it:

```bash
git add index.html && git commit -m "Initialize site"
```

Step 5. Push the branch:

```bash
git push origin site
```

Step 6. Go back to your main branch:

```bash
git checkout -f main
```

## Point GitHub Pages at the branch

Step 1. In the GitHub repository, open Settings > Pages.

Step 2. Under Build and deployment, set Source to Deploy from a branch.

Step 3. Set Branch to `site` and the folder to `/ (root)`, and save.

The site is then served at `https://<owner>.github.io/<repo>/`.

## Publish the site

Step 1. Run the `cd` phase, which runs site, stage-site, publish-site and the deploys:

```bash
mvn-devops/devops.sh run --phase cd
```

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `SITE_BRANCH` | `site` | branch the site is published to; answer the question in `secrets` to use another name |

## How it works

`git checkout -f main` restores your working tree; untracked files such as
`.devops/` are left alone.

The site stages run in the `cd` phase, after the ci stages.
`mvn-devops/devops.sh stages` prints the exact command of every stage for your
selection. Pushing uses SSH: your own key with the `maven` orchestrator, and a
deploy key that `configure` registers on the repository with Jenkins or
Concourse.

Examples:

```bash
mvn-devops/devops.sh run --phase cd            # site, stage-site, publish-site and the deploys
mvn-devops/devops.sh run --only publish-site   # push an already built and staged site again
```

The same steps as plain Maven commands, run in the project root. No profiles
or settings are needed:

```bash
# build the site, including module sites
mvn -B org.apache.maven.plugins:maven-site-plugin:3.22.0:site

# preview it at http://localhost:8000
mvn org.apache.maven.plugins:maven-site-plugin:3.22.0:run -Dport=8000

# publish target/staging to the site branch (after mvn-devops/devops.sh run --only stage-site)
mvn -B -N org.apache.maven.plugins:maven-scm-publish-plugin:3.3.0:publish-scm \
  -Dscmpublish.pubScmUrl=scm:git:git@github.com:<owner>/<repo>.git \
  -Dscmpublish.scmBranch=site
```

How the site is assembled is described in
[What your Maven project needs](project-requirements.md#site).

## Next

- [What your Maven project needs](project-requirements.md): plugins, credentials and the site build.
- [GitHub tokens and SSH keys](github-setup.md): tokens and the SSH key used to push.
