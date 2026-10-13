# Conventions

Development rules adapted from [rock3r/headroom](https://github.com/rock3r/headroom)
(`AGENTS.md` and `docs/`), combined with the upstream rules in [CONTRIBUTING.md](../CONTRIBUTING.md)
and [.github/copilot-instructions.md](../.github/copilot-instructions.md). Where the two disagree,
this file says which one wins and why.

## Code

- Package root: `com.nutomic.syncthingandroid`. Do not rename packages: the namespace and the
  `SharedPreferences` keys are part of users' config backups and of upstream merges.
- New code is Kotlin. Existing Java files are converted as steps of
  [the migration plan](plans/KOTLIN-COMPOSE-MIGRATION.md), with its conversion rules.
- Code, comments, log messages and docs are in English.
- Follow the brace and parenthesis style of the surrounding code. Unix line endings only.
- Keep Android types out of logic that can be pure (parsers, validators, run-condition decisions),
  so it can be tested on the JVM without Robolectric.
- Dagger is the DI framework already in place. A class that needs app singletons injects itself
  through `DaggerComponent`; prefer constructor parameters for new classes.
- New preferences get a `PREF_*` constant in `service/Constants.java`. Never rename or reuse an
  existing key: the import/export zip carries them.
- Everything that talks to Syncthing goes through `RestApi` when it is running and `ConfigXml`
  when it is not; UI code uses `ConfigRouter` for that.
- The REST client only ever connects to `127.0.0.1` (see `ApiRequest.forceLoopbackHost`). Do not
  change that or the TLS relaxations that depend on it.
- Strings go in `res/values/strings.xml` (English) only. Translations come from Weblate; never edit
  `values-*/strings.xml` by hand.
- Do not edit `wiki/CHANGELOG.md` or the README changelog section: the maintainer writes them at
  release time.
- Do not bump the Kotlin version in `gradle/libs.versions.toml` as a side effect of another task.

## Compose

- New UI is Compose with Material 3, inside `ApplicationTheme`.
- Follow the Compose Rules: a `Modifier` parameter on every public composable, state hoisted to
  the caller, no mutable state in parameters.
- Settings screens read and write preferences through `createPreferenceFlow()`
  (`settings/SharedPreferenceFlow.kt`) and live in their own `Settings*Screen.kt`, with a
  `SettingsRoute` entry in `SettingsNavigation.kt`.
- Every piece of meaning carried by motion also has a static form, for when animations are off.

## Git

- Commit messages and PR titles follow Conventional Commits, in lower case, with the component as
  scope and `(fixes #N)` at the end when they close an issue, e.g.
  `feat(RestApi): add local completion result cache (fixes #1000)`. This is upstream's rule and it
  wins over headroom's "no prefixes", so commits stay mergeable upstream.
- Upstream requires a DCO `Signed-off-by:` line on PR commits (`git commit -s`).
- One topic per branch. PRs are squash-merged; do not rebase a branch under review.
- Run the gate (see [STATIC-ANALYSIS.md](STATIC-ANALYSIS.md)) before every commit.
- Upstream does not accept AI-generated contributions or unsolicited PRs from new contributors.
  Work on this fork stays on the fork unless the owner decides otherwise.
