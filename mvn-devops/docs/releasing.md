# Releasing your project

`devops.sh release` releases the Maven project without maven-release-plugin, so
the pom needs no `<scm>` and no `<distributionManagement>`:

1. sets the release version in every module (`1.2.0-SNAPSHOT` becomes `1.2.0`),
   commits "Release 1.2.0" and tags `v1.2.0`
2. runs the deploy stages of the selected artifact repositories
3. sets the next development version (`1.2.1-SNAPSHOT`) and commits it
4. pushes the branch and the tag

```bash
devops.sh release --dry-run                          # show what would happen
devops.sh release                                    # 1.2.0, then 1.2.1-SNAPSHOT
devops.sh release --version 2.0.0 --next 2.1.0-SNAPSHOT
devops.sh release --no-push                          # check locally, push yourself
```

It runs on your machine with `mvn` and git, needs a clean working tree and
pushes with your own git credentials or SSH key. Nothing is pushed before
step 4: if a step fails, the release commit and the tag are rolled back. To
undo a release that was already pushed, delete the tag
(`git push origin :refs/tags/v1.2.0`) and revert the two commits.
