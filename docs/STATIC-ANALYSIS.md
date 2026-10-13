# Static analysis

Rules adapted from [rock3r/headroom](https://github.com/rock3r/headroom/blob/main/docs/STATIC-ANALYSIS.md):
zero tolerance, fix the cause, no new suppressions without a written reason.

## The gate

```bash
IS_COPILOT=true ./gradlew :app:check :app:verifyRoborazziDebug
```

`check` runs Android Lint, the unit tests, detekt and ktfmt; `verifyRoborazziDebug` compares the
screenshots. It must be green before every commit, and CI runs the same command
(`.github/workflows/check.yaml`). `IS_COPILOT=true` skips `:syncthing:buildNative`, which needs Go
and takes minutes; Java/Kotlin, tests and Lint do not need it.

## Tools

| Tool | What it checks | Config |
|---|---|---|
| Android Lint | Android correctness, security, performance, accessibility (Java and Kotlin) | `app/lint.xml`, `abortOnError = true` |
| detekt 2.0.0-alpha.6 | Kotlin code smells and complexity | `config/detekt/detekt.yml` (headroom's), on top of the defaults |
| Compose Rules 0.6.8 | Compose API and state rules, inside detekt | defaults |
| ktfmt 0.27.0 | Kotlin formatting, KotlinLang style | `app/build.gradle.kts` |

```bash
IS_COPILOT=true ./gradlew :app:detekt            # detektDebug (app) + detektDebugUnitTest (tests)
IS_COPILOT=true ./gradlew :app:ktfmtCheckMain :app:ktfmtCheckTest
IS_COPILOT=true ./gradlew :app:ktfmtFormatTest   # or ktfmtFormatMain for files off the legacy list
IS_COPILOT=true ./gradlew :app:lintDebug
```

detekt runs with type resolution: with AGP's built-in Kotlin the per-source-set tasks see no
sources. Plain `detekt` is an alias for `detektDebug` + `detektDebugUnitTest`.

## detekt: no baseline

All code, app and tests, is detekt-clean and there is no baseline file. The 80 findings that
predated detekt were fixed on 2026-10-13. Do not add a baseline: fix new findings, or suppress
one declaration with a written reason.

detekt's type resolution reports a few hundred "compiler errors found during analysis" (it does
not see some generated or Java sources with AGP's built-in Kotlin). It is a warning, not a finding,
and was already there before the cleanup.

## The ktfmt ratchet for existing code

The app code predates ktfmt and must not be reformatted in bulk (it would only create conflicts
with upstream). One list holds the existing debt and may only shrink.

- `config/ktfmt/legacy-files.txt`: 33 Kotlin files in `app/src/main` not yet ktfmt-clean. When you
  refactor one, remove it from the list and run `ktfmtFormatMain`, in a commit of its own so the
  reformat does not hide the real change.

New Kotlin files are checked in full. Java is covered by Lint only. Build scripts are not
formatted.

## Lint

Lint errors fail the build. `app/lint.xml` already ignores some checks (translations, Gradle
version notices, scoped storage, the custom trust manager, …), each with a comment.

## Suppressions

Fix the cause first. When a suppression is truly needed, put it on the smallest scope (one
declaration, at most one file) and write the reason in a comment next to it. Do not add entries to
`app/lint.xml`, add a detekt baseline, grow the ktfmt list, or disable checks, without the owner's approval.
