# Project site on GitHub Pages

The `github-pages` module builds the Maven site and pushes it to a `site`
branch, which GitHub Pages serves. Two things have to be done once by hand.

**1. Create the `site` branch** in your project repository. It is an orphan
branch that only holds the published site:

```bash
git checkout --orphan site
git rm -rf --cached . > /dev/null
echo "site" > index.html
git add index.html
git commit -m "Initialize site"
git push origin site
git checkout -f main
```

`git checkout -f main` restores your working tree; untracked files such as
`.devops/` are left alone. Use another branch name by answering the
`SITE_BRANCH` question in `secrets`.

**2. Point GitHub Pages at it.** In the GitHub repository go to
Settings > Pages, and under Build and deployment set:

- Source: Deploy from a branch
- Branch: `site`, folder `/ (root)`

The site is then served at `https://<owner>.github.io/<repo>/`.

**Publishing.** The site stages run in the `cd` phase, after the ci stages:

```bash
./devops.sh run --phase cd            # site, stage-site, publish-site and the deploys
./devops.sh run --only publish-site   # push an already built and staged site again
```

**Maven by hand.** The same steps as plain Maven commands, run in the project
root. No profiles or settings are needed:

```bash
# build the site, including module sites
mvn -B org.apache.maven.plugins:maven-site-plugin:3.22.0:site

# preview it at http://localhost:8000
mvn org.apache.maven.plugins:maven-site-plugin:3.22.0:run -Dport=8000

# publish target/staging to the site branch (after ./devops.sh run --only stage-site)
mvn -B -N org.apache.maven.plugins:maven-scm-publish-plugin:3.3.0:publish-scm \
  -Dscmpublish.pubScmUrl=scm:git:git@github.com:<owner>/<repo>.git \
  -Dscmpublish.scmBranch=site
```

`./devops.sh stages` prints the exact command of every stage for your
selection. Pushing uses SSH: your own key with the `maven` orchestrator, and a
deploy key that `configure` registers on the repository with Jenkins or
Concourse.
