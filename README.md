# FLIR ONE Pro

An Android app that shows the live picture of a FLIR ONE Pro thermal camera connected to the phone's USB port.

## Code quality checks

Two linters check the Kotlin code:

| Tool | What it checks | Check | Fix automatically |
|---|---|---|---|
| ktlint (through Spotless) | code style and formatting | `./gradlew spotlessCheck` | `./gradlew spotlessApply` |
| detekt | code smells: complexity, long methods, magic numbers, ... | `./gradlew detekt` | (by hand) |

Both must report no problems before code is committed. The detekt settings are in `config/detekt/detekt.yml`.
The unit tests run with `./gradlew testDebugUnitTest`; they are not part of the commit check.

## Git hooks

The repository has a `pre-commit` hook in the `.githooks` folder. Before every `git commit` it runs ktlint and detekt
(not the tests), and cancels the commit if they find problems. Only clean code gets into the history.

The hook checks only what is staged, that is, exactly what would be committed. It exports the staged version of the project
into a temporary folder inside `.git` and runs the linters there. Changes that are not staged, and files that are not
added, are ignored. If a commit contains no Kotlin code (`.kt`, `.kts`) and no linter settings, the check is skipped.

**Git does not run these hooks by itself.** The setting that turns them on is not stored in the repository, so every
clone needs it once. After cloning the repository, run this in its root folder:

```
git config core.hooksPath .githooks
```

On Linux and macOS the hook file must also be executable:

```
chmod +x .githooks/pre-commit
```

To check that it is on, `git config --get core.hooksPath` must print `.githooks`.

What to do when a commit is cancelled:

- If the problem is formatting, run `./gradlew spotlessApply`, look at what it changed, stage the files again and commit again.
- If detekt reports a problem, fix it in the code, stage it again and commit again.

Because the staged state is checked, every commit must be clean on its own, not only the last one of a series.

The check takes from several seconds to about a minute. It cannot be relied on alone: `git commit --no-verify` skips it, and the setting above
is local to each clone.

### Gradle and a user name with non-Latin letters (Windows)

Gradle fails if the path to the user profile contains non-Latin letters. Create a folder with an ASCII-only path, for
example `C:\gradle-ascii`, and set the environment variable `GRADLE_USER_HOME` to it. The hook uses `C:/gradle-ascii`
automatically if that folder exists and the variable is not set.
