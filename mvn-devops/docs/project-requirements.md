# What your Maven project needs

A `pom.xml` that builds with `mvn package`. Nothing else.

The stages call every plugin by its full coordinates and configure it with `-D`
properties, so the project needs no profiles, no `distributionManagement`, no
plugin declarations and no settings file of its own:

```
org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check -Dcheckstyle.config.location=google_checks.xml
org.sonarsource.scanner.maven:sonar-maven-plugin:5.8.0.7211:sonar -Dsonar.host.url=$SONAR_URL -Dsonar.token=$SONAR_TOKEN
package ... org.apache.maven.plugins:maven-deploy-plugin:3.2.0:deploy -DaltSnapshotDeploymentRepository=nexus-snapshots::$NEXUS_ARTIFACTORY_SNAPSHOT_URL ...
```

`./devops.sh stages` prints the exact commands for the selected modules.

## Why not a profiles.xml?

Maven 2 read profiles from a `profiles.xml` next to the pom; Maven 3 removed
it. Profiles in `settings.xml` can only hold properties, repositories and
activation rules, not plugins or `distributionManagement`. Calling the plugins
directly is the way to keep all of that out of the project.

## Settings and credentials

The framework passes [templates/settings.xml](../templates/settings.xml) as
global settings (`-gs`). It declares one `<server>` per deploy target, with
credentials read from environment variables the modules export:

| Server id | Credentials |
|---|---|
| `github` | `GITHUB_USERNAME`, `GITHUB_PACKAGE_TOKEN` |
| `nexus-snapshots`, `nexus-releases` | `NEXUS_ARTIFACTORY_USERNAME`, `NEXUS_ARTIFACTORY_PASSWORD` |
| `jfrog-snapshots`, `jfrog-releases` | `JFROG_ARTIFACTORY_USERNAME`, `JFROG_ARTIFACTORY_ENCRYPTED_PASSWORD` |

Jenkins and Concourse write the same file into the build container before the
first stage.

## Optional knobs

All of them are questions asked by `secrets`:

| Value | Default | |
|---|---|---|
| `MAVEN_SETTINGS` | empty | the project's own settings file, added with `-s` |
| `MAVEN_PROFILES` | empty | project profiles to activate in every stage |
| `MAVEN_ATTACH_SOURCES` | yes | deploy sources and javadoc jars too |
| `MAVEN_CHECKSTYLE` | yes | run the checkstyle stage |
| `MAVEN_CHECKSTYLE_CONFIG` | `google_checks.xml` | `sun_checks.xml`, a URL, or a file in the project such as `code-style/checkstyle.xml` |
| `SITE_BRANCH` | `site` | branch the site is published to |

Plugin versions live in [lib/maven.sh](../lib/maven.sh). Override one for a
project by storing a value, for example `MVN_JAVADOC_VERSION`.

If the pom already configures one of these plugins (checkstyle rules, Sonar
exclusions, site reports in `<reporting>`), that configuration still applies;
the command line only fills in what the pom leaves open.

## Site

The site stage builds the site with the reports in the pom's `<reporting>`
section, or the default project information pages when there is none.
`maven-site-plugin`'s own `stage` goal requires `<distributionManagement><site>`
in the pom, so a short shell step (`stage-site`) assembles `target/staging`
instead: the root site plus each first-level module's site in a sub directory.
`publish-site` pushes it with `maven-scm-publish-plugin` to
`git@github.com:<owner>/<repo>.git`, branch `site`. Pushing uses SSH: your own
key when the `maven` orchestrator runs on your machine, and a deploy key
(generated and registered on the repository by `configure`) when Jenkins or
Concourse runs the build.

Create the `site` branch once and point GitHub Pages at it, as described in
[Project site on GitHub Pages](github-pages.md).

## Tokens

See [github-setup.md](github-setup.md) for the GitHub tokens, their scopes and
the SSH key.
