# Static analysis

Rules adapted from [rock3r/headroom](https://github.com/rock3r/headroom/blob/main/docs/STATIC-ANALYSIS.md):
zero tolerance, fix the cause, no new suppressions without a written reason.

## The gate

```bash
IS_COPILOT=true ./gradlew :app:check   # lintDebug + unit tests, no Go build (~5 s when warm)
```

It must be green before every commit. `IS_COPILOT=true` skips `:syncthing:buildNative`, which needs
Go and takes minutes; Java/Kotlin and Lint do not need it. Before a release or any change to the
native build, run the full build without it (see [AGENTS.md](../AGENTS.md#build--test)).

## Tools

| Tool | Status | What it checks | Config |
|---|---|---|---|
| Android Lint | Active, `abortOnError = true` | Android correctness, security, performance, accessibility | `app/lint.xml` |
| detekt 2 + Compose Rules (Nacho Lopez) | Not set up yet | Kotlin smells, complexity, Compose API rules | to add: `config/detekt/detekt.yml` (headroom's) |
| ktfmt (KotlinLang style) | Not set up yet | Kotlin formatting | to add |

Lint currently reports no issues on the debug variant. CI runs `lint<Type>` before assembling.

### Introducing detekt and ktfmt

Headroom runs detekt, Compose Rules and ktfmt on every module. Here they would hit ~40 existing
Kotlin files and reformat code that upstream also changes, so they are introduced with a ratchet:

- Scope them to Kotlin sources. Java is left alone.
- Run ktfmt only on files a change touches, never as a repository-wide reformat.
- Start detekt from headroom's `config/detekt/detekt.yml`. A baseline for the existing code needs
  the owner's approval; new code must be clean without it.

Until then, write new Kotlin as if they were on: no wildcard imports, lines up to 120 characters,
functions under 100 lines, complexity at most 15.

## Lint

Lint errors fail the build. `app/lint.xml` already ignores some checks (translations, Gradle
version notices, scoped storage, the custom trust manager, …), each with a comment.

## Suppressions

Fix the cause first. When a suppression is truly needed, put it on the smallest scope (one
declaration, at most one file) and write the reason in a comment next to it. Do not add new entries
to `app/lint.xml`, add baselines, or disable checks without the owner's approval.
