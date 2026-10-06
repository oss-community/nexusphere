# IDE settings (IntelliJ IDEA)

## Checkstyle

The `checkstyle` stage checks the code with `MAVEN_CHECKSTYLE_CONFIG`
(`google_checks.xml` unless you chose your own file). To see the same warnings
while you type:

1. Install the [CheckStyle-IDEA](https://plugins.jetbrains.com/plugin/1065-checkstyle-idea) plugin.
2. Settings > Tools > Checkstyle: add a configuration file. Use the project's
   own file (e.g. `code-style/checkstyle.xml`) or the bundled Google checks,
   whichever the pipeline uses, and make it active.
3. To format code the same way: Settings > Editor > Code Style > Java >
   (gear icon) > Import Scheme > CheckStyle Configuration, and pick the same file.

## Test coverage

If IntelliJ's own coverage runner reports wrong numbers or slows tests down,
open Help > Find Action > `Registry...` and turn off:

- `idea.coverage.new.sampling.enable`
- `idea.coverage.test.tracking.enable`
- `idea.coverage.tracing.enable`

## Pipeline variables in the IDE

Run configurations started from the IDE do not see the pipeline variables.
`devops.sh render` writes `.devops/generated/pipeline.sh` (run it as a shell
run configuration), and on Windows `devops.sh env --windows` writes
`.devops/generated/set-env.bat`, which sets them with `setx` for the IDE.
