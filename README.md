# FLIR ONE Pro

An Android app that shows the live picture of a FLIR ONE Pro thermal camera connected to the phone's USB port.

## License

Copyright (c) 2026 Excited Dev.

The source code is available under the [PolyForm Noncommercial License 1.0.0](LICENSE)
(SPDX: `PolyForm-Noncommercial-1.0.0`). In short:

- You may use, study, change and share the code for **noncommercial purposes**: personal use, hobby projects, research,
  education, and use by charities, schools, public research, public safety or health bodies, and governments.
- You may **not** use it for commercial purposes, for example to sell it, to include it in a paid product or service, or
  to use it for the profit of a company.
- If you share the code, you must include the `LICENSE` file (with its `Required Notice:` line).

This is a source-available license, not an open-source license as defined by the OSI. The summary above is only a
help; the text in [LICENSE](LICENSE) is what counts.

### Third-party software

The app uses AndroidX and Material Components (Apache License 2.0) as Gradle dependencies; they are not part of this
repository and keep their own licenses. A build of the app that contains them must follow the terms of those licenses.

### Trademarks and no affiliation

FLIR, FLIR ONE and Lepton are trademarks of their owners (Teledyne FLIR LLC). This is an independent project. It is not
affiliated with, authorized by or endorsed by Teledyne FLIR. It contains no FLIR SDK code; the camera protocol was
implemented independently by observing the behavior of a camera owned by the author. The names are used only to say which
hardware the app works with.

### No warranty

The software comes as is, without any warranty. The app is not a certified measuring instrument: temperatures it shows
are approximate and must not be used for safety-critical decisions.

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
