# AGENTS.md

Shared knowledge base for every coding agent (Claude Code, Amp, Antigravity, OpenCode, Copilot).
Read it at the start of every task and keep it current.

## INFO

- **Syncthing-Fork for Android**: an Android wrapper that runs the Syncthing daemon (Go) as a
  child process and controls it through its local REST API.
- Shown to users as **Expressive Syncthing** (`fork_app_name`, not translated; since 2026-10-18).
  Upstream's `app_name` ("Syncthing-Fork", translated on Weblate) stays for the version extra
  Syncthing reports (`STVERSIONEXTRA`) and for upstream merges.
- This checkout is `tiwiz/syncthing-android`, a fork of `researchxxl/syncthing-android`
  (itself forked from `syncthing/syncthing-android`). Application id
  `com.github.catfriend1.syncthingfork`, namespace `com.nutomic.syncthingandroid`.
- Kotlin only, every screen in Jetpack Compose + Material 3 (migrated from Java/Views in
  October 2026). Hilt (Dagger 2.60, KSP), Volley + Gson for HTTP, Navigation 3 in settings. Go for the native part.
- minSdk 30 (Android 11), compileSdk/targetSdk 37, JDK 21, AGP 9.4, Kotlin 2.4, Gradle 9.8.

## SOURCE OF TRUTH DOCS

| Document | Use it for |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, packages, service lifecycle, REST client, run conditions, UI flow |
| [docs/CONVENTIONS.md](docs/CONVENTIONS.md) | Code, Compose and git rules |
| [docs/TESTING.md](docs/TESTING.md) | TDD flow, test layers, what to test first |
| [docs/STATIC-ANALYSIS.md](docs/STATIC-ANALYSIS.md) | The gate, Lint, planned detekt/ktfmt, suppressions |
| [docs/TECH-DEBT.md](docs/TECH-DEBT.md) | Things to fix: pinned bugs, detekt suppressions, TODOs |
| [docs/plans/HILT-VIEWMODELS.md](docs/plans/HILT-VIEWMODELS.md) | Done (H1–H9): Hilt instead of hand-written Dagger, screen state in ViewModels, service reached through `SyncthingConnection` |
| [docs/plans/M3-EXPRESSIVE-HANDOFF.md](docs/plans/M3-EXPRESSIVE-HANDOFF.md) | Material 3 Expressive work: UI architecture, pitfalls, detailed plan for steps 8–13, split between two agents |
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

Keep changes to what the task asks for. Unrequested behaviour changes are regressions. No drive-by
reformatting or renames. Java→Kotlin and View→Compose conversions happen only as steps of the
migration plan ([docs/plans/KOTLIN-COMPOSE-MIGRATION.md](docs/plans/KOTLIN-COMPOSE-MIGRATION.md)),
which the owner started on 2026-10-13; the fork no longer tracks a small diff against upstream.

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
# Instrumented WebView tests (Espresso-Web), emulator only (AVDs pixel_phone API 35, api34_phone API 34):
emulator -avd pixel_phone -no-window -no-audio &
ANDROID_SERIAL=emulator-5554 IS_COPILOT=true ./gradlew :app:connectedDebugAndroidTest

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
    SyncthingApp, SyncthingModule   @HiltAndroidApp application, app-wide module, AppDependencies
    service/       SyncthingService, SyncthingRunnable, RestApi, EventProcessor,
                   RunConditionMonitor, NotificationHandler, QS tiles, Constants (PREF_* keys)
    http/          Volley requests to the REST API (loopback only, custom trust manager)
    model/         Gson POJOs for Syncthing's JSON + local caches
    util/          ConfigXml (config.xml DOM), ConfigRouter, FileUtils, PermissionUtil, …
    activities/    Compose screens: Main (+ overflow menu, detail pane on large windows), Folder, Device, pickers, dialogs, Share,
                   PhotoShoot, QR scanner, Log, web page, their ViewModels; `ThemedAppCompatActivity` base class
    fragments/     DeviceIdDialogFragment (Compose)
    views/         FolderRowState, DeviceRowState (main screen list rows)
    onboarding/    Compose launcher flow (permissions, key generation)
    settings/      Compose settings, Navigation 3, SharedPreferenceFlow
    ui/            shared expressive components: state badges, grouped lists, wavy progress
    recentchanges/ webgui/ theme/ receiver/
  src/main/res/    strings, drawables, themes, a few XML layouts (values = English, values-* from Weblate)
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

- Kotlin and Compose + Material 3 (`ApplicationTheme`) only; no new Java or View screens.
- English everywhere (code, comments, logs, docs). Unix line endings. Match surrounding style.
- Config access through `ConfigRouter` (REST when running, `config.xml` when stopped).
- REST only to `127.0.0.1`; never weaken `ApiRequest`/`SyncthingTrustManager`.
- New preferences: `PREF_*` constants in `Constants.kt`; never rename existing keys.
- Strings only in `res/values/strings.xml`; translations come from upstream's Weblate, so fork-only
  strings stay English ([docs/plans/M3-EXPRESSIVE.md](docs/plans/M3-EXPRESSIVE.md)).
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
   36 tests, coverage 46%. They pinned 4 more bugs; fixed so far: event polling stopping after
   one failed request (PR #2), startup stuck in `STARTING` after one failed request (PR #3),
   `EventProcessor` quoted path and switch fall-through (PR #4), size
   formatting crash at 1 PiB (PR #5), `ConfigXml.addFolder` with null
   versioning (PR #6), `ConfigXml.removeDevice` unsharing folders (PR #7),
   ignoring devices/folders while stopped (PR #8), `Compression` default
   (PR #9). No pinned bugs left. Then TECH-DEBT TODOs: empty backup path after import (PR #10),
   WebView proxy bypass through `androidx.webkit` instead of reflection (PR #11),
   settings navigator as a parameter instead of a CompositionLocal (PR #12), bound
   service as a `SyncthingServiceState` parameter (PR #13), activity scope as a parameter
   (PR #14): no CompositionLocal suppressions left. Decided (PR #15): the broad catch in
   `syncWebGuiTheme` stays; onboarding keeps its 360dp compact rule instead of window size classes.
4. Test gaps filled (PRs #16–#20): REST client, running-state and remaining screenshots, native
   process and service lifecycle. Coverage 60%. What is left: [docs/TESTING.md](docs/TESTING.md#not-covered-yet).
   2026-10-13, PRs #21–#25: receivers, versioning dialog, edit screens, onboarding, tiles (coverage
   66%). PR #26: Robolectric tests for `WebGuiActivity`/`WebViewActivity` and the first
   instrumented tests (`app/src/androidTest`, Espresso-Web, CI job `Instrumented tests`). They
   pinned a blank web GUI after a refused certificate, fixed in PR #27. No
   pinned bugs left. detekt now sees the app's Java classes (javac output on
   `detektDebug`'s classpath, PR #28): no more "compiler errors found during analysis".
   PR #29: folder type, pull order and sync conditions screens, QR scan, share. Moving
   `enableEdgeToEdge()` to `ThemedAppCompatActivity` was tried and dropped (PR #30, TECH-DEBT.md).
5. 2026-10-13: Kotlin/Compose migration started
   ([docs/plans/KOTLIN-COMPOSE-MIGRATION.md](docs/plans/KOTLIN-COMPOSE-MIGRATION.md)). PR #31:
   `model/` in Kotlin (`CompletionCacheTest`). PR #32: `http/` (`ApiRequestTest`). PR #33: small
   `util/` helpers, Dagger, app, receivers, small services (`HelpersTest`, `ServiceHelpersTest`).
   PR #34: `Util` split into the `Util` (shell, shadowed in tests), `TextFormat`, `UiUtil` and
   `OsTrustStore` facades (`UtilShellTest`). PR #35: `FileUtils` split into
   `FileUtils`, `SafUtils`, `FileOpener` (`FileUtilsStorageTest`, full MIME golden). PR #36:
   `ConfigXml` (+ `ConfigXmlModels`, `ConfigXmlNodes`, `ConfigXmlValues`) and `ConfigRouter`
   (`ConfigXmlEditTest`, REST routing test). PR #37: `Constants` (`ConstantsTest` pins
   the per-object preference keys) and the two tiles. PR #38: `NotificationHandler`
   (`NotificationHandlerTest`). PR #39: `SyncthingRunnable` (log trimming
   and gateway tests). PR #40: `EventProcessor`. PR #41:
   `RunConditionMonitor` + `RunConditionChecks` + `DeviceState` (`RunConditionTimelineTest`).
   PR #42: `RestApi` + `RestApiSupport` (`RestApiCacheTest`). PR #43:
   `SyncthingService` + `ConfigBackup`, `HttpsCertFiles`,
   `ObjectRunConditions` (`SyncthingServiceStoppedTest`). All non-UI code is Kotlin now. Screens to
   Compose: PR #44 folder type and pull order dialogs (`SingleChoiceDialogActivity`). PR #45
   versioning dialog (`VersioningScreen`). PR #46
   `SyncConditionsScreen`. PR #47 QR
   scanner and Syncthing Camera. PR #48 log
   screen. PR #49
   folder picker. PR #50 share
   screen. PR #51 web
   page. PR #52
   device screen (`DeviceActivityTest` through the `DeviceScreen` driver). PR #53
   folder screen (`FolderActivityTest` through `FolderScreen`).
   PR #55: `views/FolderRowState` and `views/DeviceRowState` (what a list row shows,
   `FolderRowStateTest`, `DeviceRowStateTest`); `FoldersAdapter` and `DevicesAdapter` in Kotlin.
   PR #56: `MainActivityTest` (17) and `MainActivityPermissionTest` through the `MainScreen`
   driver. PR #57: the main screen in Compose (`MainActivity`, `MainContent`, `MainRows`,
   `MainDrawer`, `SystemFigures`). PR #58: `SyncthingActivity`
   and `ThemedAppCompatActivity` in Kotlin, `enableEdgeToEdge()` in the base class, unused
   `EnhancedEditText`/`TipListAdapter` and orphan resources removed: no Java left in the app.
   2026-10-16, milestone 1 (PRs #31–#58) closed: native debug build installed on the Pixel 8
   (Android 17) over the existing data, every migrated screen walked through (main tabs, drawer,
   device ID, web GUI, usage reporting, folder/device create, recent changes, settings, log); no
   crashes in logcat, native process running. The migration is complete. Follow-ups fixed:
   status tab labels in Italian (PR #60), missing space after the time in the Android log
   (PR #61). TECH-DEBT has no TODOs left.
6. 2026-10-16: Material 3 Expressive and adaptive layouts (phone, foldable, tablet) proposed in
   [docs/plans/M3-EXPRESSIVE.md](docs/plans/M3-EXPRESSIVE.md), with the approved design canvas.
   Decisions taken the same day (in the plan), plus minSdk 30 (PR #64). PR 1 theme foundation
   (`MaterialExpressiveTheme`, fallback colors, Roboto Flex). PR 2 window size screenshot
   matrix (`WindowSizeScreenshotTest`). PR 3 shared components (`ui/`,
   `StateTone`). PR 4 navigation bar/rail and
   overflow menu. PR 5 folders list and add FAB
     menu. PR 6 devices list. PR 7 status tab. PR 10e onboarding page icon in an expressive badge
     (#87). PR 11a grouped Material 3 Expressive settings rows and classic `LargeTopAppBar` (#88).
     PR 11b list-detail settings layout on expanded windows (#89).
     PR 12 motion and polish (#90): morphing selected/pressed rows, `LoadingIndicator` while
     starting, predictive back shrinking the detail pane, wave only with animations on.
     Steps 8–12 done; milestone 13 done on 2026-10-18 (see point 8), as planned in
     [docs/plans/M3-EXPRESSIVE-HANDOFF.md](docs/plans/M3-EXPRESSIVE-HANDOFF.md).
7. 2026-10-16: Hilt and ViewModels (state survives configuration changes) planned in
   [docs/plans/HILT-VIEWMODELS.md](docs/plans/HILT-VIEWMODELS.md), interleaved with the Expressive
   steps (order at the end of that file). Done 2026-10-17: H1 Hilt (#78), H2 constructor
   dependencies (#79), H3 `SyncthingConnection` (#81), H4 `MainViewModel` (#83), H5 folder and
   device ViewModels (#84). 2026-10-17: M3 Expressive step 9, folder and device screens.
    2026-10-17: B finished steps 10 and 11 (#85, #87, #88, #89) and started step 12 (#90),
    which A finished. 2026-10-17: H6 onboarding `OnboardingViewModel` (B, #92), H7 settings
    ViewModels per navigation entry (A, #93); H8 remaining screens (A, three PRs: H8a log, folder
    picker, recent changes; H8b share, photo shoot, sync conditions, dialogs; H8c web GUI and web
    page); H9 no per-activity service binding (A, #97). Coverage after H9: 85% of lines, 87% after the milestone 13
    test pass ([docs/TESTING.md](docs/TESTING.md#coverage)).
8. 2026-10-18, milestone 13: native debug build on the Pixel 8 (Android 17) over the existing data,
   plus AVDs `fold` (7.6in foldable profile, API 35; `avdmanager` has no `pixel_fold`) and `tablet`
   (`pixel_tablet`, API 35). Every screen walked in light/dark, English/Italian, portrait/landscape;
   onboarding on both emulators. No crashes, the service starts everywhere. Fixed: keyboard popping
   up when a detail pane opened, bottom bar and large titles on phones held sideways (#99);
   AppCompat-framed choice dialogs, stale "only while running" hint, stale row selection, empty
   message under the recent changes spinner (#100). Tests for the weakest files (#101, coverage
   87%) pinned two bugs, fixed in #103: a failed support bundle stuck as downloading, and the web
   GUI theme not written while stopped when `config.xml` has no `<user>`. Left as is: "General" and
   "No debug facility" untranslated in Italian (upstream keys, Weblate). Not checked: the folded
   outer screen (the emulator profile does not change size when folded) and the QR scanner
   camera. GitHub milestone "Material 3 Expressive & ViewModels" holds PRs #62–#101.
   **Resume here:** whatever the owner picks next; TECH-DEBT has no bugs left.

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
- `connectedDebugAndroidTest` installs and then uninstalls the debug app with its data: with a phone
  plugged in, always set `ANDROID_SERIAL` to the emulator. Disable animations on the emulator.
