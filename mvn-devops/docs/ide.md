# IDE settings (IntelliJ IDEA)

This page sets up IntelliJ IDEA so it shows the same checkstyle warnings as the
pipeline, measures test coverage correctly and sees the pipeline variables.
Read it when you work on the project in IntelliJ. On Windows, run `devops.sh`
as `mvn-devops\devops.bat`.

## Before you start

- The checkstyle file the pipeline uses: `MAVEN_CHECKSTYLE_CONFIG`
  (`google_checks.xml` unless you chose your own file; see
  [What your Maven project needs](project-requirements.md)).

## Show checkstyle warnings while you type

Step 1. Install the [CheckStyle-IDEA](https://plugins.jetbrains.com/plugin/1065-checkstyle-idea) plugin.

Step 2. In Settings > Tools > Checkstyle, add the configuration file the pipeline uses (the project's own file, e.g. `code-style/checkstyle.xml`, or the bundled Google checks) and make it active.

Step 3. To format code the same way, open Settings > Editor > Code Style > Java > (gear icon) > Import Scheme > CheckStyle Configuration and pick the same file.

## Fix the coverage runner

Use this when IntelliJ's own coverage runner reports wrong numbers or slows
tests down.

Step 1. Open Help > Find Action > `Registry...`.

Step 2. Turn off `idea.coverage.new.sampling.enable`, `idea.coverage.test.tracking.enable` and `idea.coverage.tracing.enable`.

## Use the pipeline variables in the IDE

Run configurations started from the IDE do not see the pipeline variables.

Step 1. Write `.devops/generated/pipeline.sh`:

```bash
mvn-devops/devops.sh render
```

Step 2. Add `.devops/generated/pipeline.sh` as a shell script run configuration and run it.

## Use the pipeline variables in the IDE on Windows

Step 1. Write `.devops/generated/set-env.bat`, which sets the variables with `setx`:

```bash
mvn-devops/devops.sh env --windows
```

Step 2. Run `.devops/generated/set-env.bat` and restart the IDE.

## Next

- [What your Maven project needs](project-requirements.md): the checkstyle settings.
- [Troubleshooting](troubleshooting.md): when something does not work.
