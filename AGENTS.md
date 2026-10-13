# AGENTS.md

Shared knowledge base for every coding agent (Claude Code, Amp, Antigravity, OpenCode, Copilot).
Read it at the start of every task and keep it current.

## INFO

- **Syncthing-Fork for Android**: an Android wrapper that runs the Syncthing daemon (Go) as a
  child process and controls it through its local REST API.
- This checkout is `tiwiz/syncthing-android`, a fork of `researchxxl/syncthing-android`
  (itself forked from `syncthing/syncthing-android`). Application id
  `com.github.catfriend1.syncthingfork`, namespace `com.nutomic.syncthingandroid`.
- Java (older code, Views + AppCompat) and Kotlin (newer code, Jetpack Compose + Material 3).
  Dagger 2 via KSP, Volley + Gson for HTTP, Navigation 3 in settings. Go for the native part.
- minSdk 23, compileSdk/targetSdk 37, JDK 21, AGP 9.4, Kotlin 2.4, Gradle 9.8.

## SOURCE OF TRUTH DOCS

| Document | Use it for |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, packages, service lifecycle, REST client, run conditions, UI flow |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | Code, Compose and git rules |
| [docs/TESTING.md](docs/TESTING.md) | TDD flow, test layers, what to test first |
| [docs/STATIC-ANALYSIS.md](docs/STATIC-ANALYSIS.md) | The gate, Lint, planned detekt/ktfmt, suppressions |
| [docs/TECH-DEBT.md](docs/TECH-DEBT.md) | Things to fix: pinned bugs, detekt suppressions, TODOs |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Upstream rules (commits, DCO, AI policy) |
| [wiki/developers/](wiki/developers/) | Upstream build guide, sync-status broadcast API |

## NON-NEGOTIABLES

Development rules copied from [rock3r/headroom](https://github.com/rock3r/headroom) and adapted.

### TDD first

1. Write the test.
2. Run the targeted test and see it fail for the right reason.
3. Write the minimum production code to make it pass.
4. Re-run the targeted test, then the gate.

Before refactoring, the safety net (characterization tests, golden files, screenshots) must be
green and must stay green without re-recording ([docs/TESTING.md](docs/TESTING.md)).

### Zero tolerance for static analysis

`IS_COPILOT=true ./gradlew :app:check :app:verifyRoborazziDebug` must be green before every commit
(Lint, unit tests, screenshots, detekt + Compose Rules, ktfmt). CI runs the same command.

- Do not edit `app/lint.xml` or `config/detekt/detekt.yml`, add a detekt baseline, grow the ktfmt
  list, or disable checks without the owner's approval.
- detekt has no baseline: app and test code are clean. Existing app code is under a ktfmt ratchet:
  `config/ktfmt/legacy-files.txt` may only shrink ([docs/STATIC-ANALYSIS.md](docs/STATIC-ANALYSIS.md)).
- Fix the cause. When a suppression is truly needed, keep it to one declaration or file and
  write down why next to it.

### Scope discipline

Keep changes to what the task asks for. Unrequested behaviour changes are regressions. Keep the
diff against upstream small: no drive-by reformatting, renames or Java→Kotlin conversions.

### Secrets

Never commit signing keys, `local.properties`, `keys.json`, real device ids, API keys or
`config.xml` files from a real device. Test fixtures are fake and anonymised
(`app/src/test/resources/fixtures/README.md`). Release signing reads `SYNCTHING_RELEASE_STORE_FILE`,
`SIGNING_PASSWORD` and `SYNCTHING_RELEASE_KEY_ALIAS` from `local.properties` or the environment.

## BUILD & TEST

Local environment: JDK 21, `ANDROID_HOME=/opt/android-sdk` (NDK 30.0.16248370 installed by
Gradle), Go 1.27.1 in `~/.local/go` (on `PATH` via `~/.profile`; in a non-login shell run
`export PATH="$HOME/.local/go/bin:$PATH"`), Syncthing submodule initialised. If the system Go
version differs from `go_version`, `build-syncthing.py` rebuilds Go from source, so keep them equal.

```bash
# Day-to-day (no Go needed, the APK has no native binary)
IS_COPILOT=true ./gradlew :app:compileDebugKotlin :app:compileDebugJavaWithJavac
IS_COPILOT=true ./gradlew :app:check :app:verifyRoborazziDebug   # THE GATE (~2 min warm)
IS_COPILOT=true ./gradlew :app:testDebugUnitTest --tests '*ConfigXmlTest'   # one test class
IS_COPILOT=true ./gradlew :app:testDebugUnitTest -Pgolden.record=true       # re-record golden files
IS_COPILOT=true ./gradlew :app:recordRoborazziDebug     # re-record screenshots (review the diff!)
IS_COPILOT=true ./gradlew :app:detekt :app:ktfmtCheckMain :app:ktfmtCheckTest
IS_COPILOT=true ./gradlew :app:lintDebug        # report: app/build/reports/lint-results-debug.html
IS_COPILOT=true ./gradlew -Pcoverage=true :app:verifyRoborazziDebug :app:createDebugUnitTestCoverageReport
                                                # coverage: app/build/reports/coverage/test/debug/index.html

# Full build with the native binary
git submodule update --init --recursive         # Syncthing sources
# install Go = go_version in gradle/libs.versions.toml (currently 1.27.1)
./gradlew buildNative                           # → app/src/main/jniLibs/<abi>/libsyncthingnative.so
./gradlew lintDebug assembleDebug               # → app/build/outputs/apk/debug/app-debug-unsigned.apk (~5 min first time)
# debug builds are unsigned (signingConfig = null); sign like build.cmd does:
/opt/android-sdk/build-tools/36.0.0/apksigner sign --ks ~/.android/debug.keystore \
  --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --out app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/debug/app-debug-unsigned.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew clean                                 # also removes jniLibs and the Go build dirs
```

Release builds (`assembleRelease`) need the signing keys and are not built locally. Debug builds
use the `.debug` suffix and GUI port 8385, so they install next to the release app.

Useful on a device: `adb forward tcp:18384 tcp:8384` (8385 for debug) then
`https://127.0.0.1:18384` opens the Syncthing web GUI; `adb logcat | grep -E 'Syncthing|SyncthingNative'`.

## STRUTTURA

```
app/                         Android application (only Gradle module with code)
  src/main/java/com/nutomic/syncthingandroid/
    SyncthingApp, DaggerComponent, SyncthingModule   Application + Dagger graph
    service/       SyncthingService, SyncthingRunnable, RestApi, EventProcessor,
                   RunConditionMonitor, NotificationHandler, QS tiles, Constants (PREF_* keys)
    http/          Volley requests to the REST API (loopback only, custom trust manager)
    model/         Gson POJOs for Syncthing's JSON + local caches
    util/          ConfigXml (config.xml DOM), ConfigRouter, FileUtils, PermissionUtil, …
    activities/    Java/Views screens: Main, Folder, Device, pickers, Share, PhotoShoot, Log
    fragments/     Folder/Device/Status tabs, versioning dialogs, Compose drawer
    views/         RecyclerView adapters, custom widgets
    onboarding/    Compose launcher flow (permissions, key generation)
    settings/      Compose settings, Navigation 3, SharedPreferenceFlow
    recentchanges/ webgui/ theme/ receiver/
  src/main/res/    XML layouts, strings (values = English, values-* from Weblate)
  src/main/play/   Play Store listing metadata
  src/main/cpp/    empty ndk-build stub so AGP packages the ABIs
  src/test/        JUnit/Robolectric tests (java/), fixtures + golden files (resources/),
                   Roborazzi golden screenshots (screenshots/)
  lint.xml         Lint configuration
syncthing/                   :syncthing project, build-syncthing.py (Go cross-compile)
  src/github.com/syncthing/syncthing   upstream Syncthing submodule
gradle/libs.versions.toml    all versions, also read by Python scripts and CI
scripts/                     SDK setup, Go version bump, debug helpers (Windows .cmd), receiver test app
wiki/                        upstream user/developer documentation
config/detekt/, config/ktfmt/  detekt config (from headroom), ktfmt legacy-file list
docs/                        architecture, development rules, plans (this fork)
.github/                     CI (build, sign, release, submodule/Go bumps), Copilot instructions
```

## CONVENZIONI

Summary; details in [docs/CONVENTIONS.md](docs/CONVENTIONS.md).

- New code in Kotlin, new UI in Compose + Material 3 (`ApplicationTheme`). Do not convert Java
  files unless asked.
- English everywhere (code, comments, logs, docs). Unix line endings. Match surrounding style.
- Config access through `ConfigRouter` (REST when running, `config.xml` when stopped).
- REST only to `127.0.0.1`; never weaken `ApiRequest`/`SyncthingTrustManager`.
- New preferences: `PREF_*` constants in `Constants.java`; never rename existing keys.
- Strings only in `res/values/strings.xml`; translations come from Weblate.
- Do not touch the changelog; do not bump Kotlin as a side effect.
- Commits: Conventional Commits, lower case, component scope, `(fixes #N)`; `Signed-off-by` for
  upstream PRs. One topic per branch, squash merge.

## STATO ATTUALE

- Branch `main` (PR #1 `baseline` merged on 2026-10-13), in sync with `origin/main` (`tiwiz/syncthing-android`). Version 2.1.7.0,
  Syncthing submodule pinned to v2.1.7-rc.1.
- Recent upstream work: custom HTTPS certificate validation (#356), Android 17 local network and
  cross-profile permissions, Compose migration of onboarding/settings/web GUI/recent changes.
- Verified on 2026-10-12: `IS_COPILOT=true ./gradlew :app:check` passes, Lint reports no issues.
- 2026-10-12: Go installed, submodule initialised, `buildNative` + `assembleDebug` succeed; the
  signed debug APK (~80 MB, 4 ABIs) is produced. Smoke-tested on a Pixel 8 (Android 17, API 37):
  onboarding completes, keys are generated, the service goes STARTING → ACTIVE, the native process
  runs and the GUI answers on `https://127.0.0.1:8385`, no errors in logcat.
- Development rules from headroom added in `AGENTS.md` and `docs/`.
- 2026-10-13, branch `baseline` (PR to `main`): refactoring safety net without touching app code
  (plan and outcome in [docs/plans/BASELINE.md](docs/plans/BASELINE.md)):
  58 JUnit/Robolectric characterization tests with golden files, 58 Roborazzi screenshots,
  detekt + Compose Rules + ktfmt with a ratchet, CI workflow `check.yaml`.
- 2026-10-13, same branch: all 80 detekt findings in app code fixed, detekt baseline removed
  (details at the end of [docs/plans/BASELINE.md](docs/plans/BASELINE.md)). No golden file or
  screenshot changed.
- 2026-10-13, same branch: opt-in JaCoCo coverage (`-Pcoverage=true`), then `RestApiTest` and
  `EventProcessorTest` against a fake Syncthing (test deps: `mockwebserver3`, `okhttp-tls`,
  `httpclient`). Coverage 37% → 46%.

Next steps (to be confirmed with the owner):

1. ~~Coverage report~~ done: JaCoCo, opt-in with `-Pcoverage=true`, 37% of lines
   ([docs/TESTING.md](docs/TESTING.md#coverage)).
2. ~~Tests for `RestApi`/`EventProcessor`~~ done: `FakeSyncthing` (MockWebServer over HTTPS),
   36 tests, coverage 46%. They pinned 4 more bugs; event polling stopping after one failed
   request is fixed on `fix/event-polling-stops`.
3. Start refactoring; work through [docs/TECH-DEBT.md](docs/TECH-DEBT.md) one PR at a time.

## HANDOFF

- Work only on `tiwiz/syncthing-android` (branches, PRs, issues). Never touch the upstream repos
  unless the owner says so. `gh pr create` in a fork may default to the parent: always pass
  `--repo tiwiz/syncthing-android --base main`.
- Upstream (`researchxxl/syncthing-android`) does not accept AI-generated contributions or
  unsolicited PRs.
- `RestApi.shutdown()` runs `Util.killProcess("find")`: in tests keep `ShadowUtilDeviceShell` on,
  or it kills `find` processes on the host ([docs/TESTING.md](docs/TESTING.md)).
- Robolectric runs SDK 36 and needs `--add-opens java.base/jdk.internal.access` on JDK 21 (set in
  `app/build.gradle.kts`). Test-only shadows in `app/src/test/.../testutil/` fill Robolectric gaps
  and replace host-dependent shell calls; never change app code to make a test run.
- To pull upstream changes: `git remote add upstream https://github.com/researchxxl/syncthing-android`
  then merge `upstream/main`. Keep fork-only changes in new files where possible.
- `IS_COPILOT=true` is just the env flag that skips `:syncthing:buildNative`; it is not tied to
  Copilot.
- The native binary is an executable named `libsyncthingnative.so`, run from `nativeLibraryDir`.
  `useLegacyPackaging = true` must stay, or installs from bundles have no binary.
- `SyncthingService` state changes must go through `onServiceStateChange()` under `mStateLock`;
  `shutdown()` defers itself while `STARTING`.
- Two installs on different user profiles collide on the GUI port; the service refuses to start
  when the port is taken (`webui_tcp_port_unavailable`).
- The sync-complete broadcast only reaches packages hard-coded in `RestApi.sendBroadcastToApps`.
- Import/export zips include `SharedPreferences`; key renames break old backups.
