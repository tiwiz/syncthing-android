# Plan: safety net before refactoring ("baseline")

Goal: be able to refactor the app and know immediately when behaviour or UI changes. Everything in
this plan adds tooling and tests **around** the app; it does not change a single line under
`app/src/main`.

## Hard constraints

1. `git diff main -- app/src/main` is empty at the end.
2. The runtime classpaths of the app (`debugRuntimeClasspath`, `releaseRuntimeClasspath`) are
   identical to `main`: new dependencies are `testImplementation` only. Checked by diffing
   `./gradlew :app:dependencies` output captured before and after.
3. The debug APK still builds (`assembleDebug` with the native binary) and Lint stays green.
4. Tests document current behaviour, including oddities. A test that reveals a bug asserts the
   current behaviour and gets a `// CHARACTERIZATION:` comment; it is fixed later, in its own PR.
5. Work happens on `tiwiz/syncthing-android` only; the PR targets its `main`.

## Phase 1: unit test infrastructure (JUnit 4 + Robolectric)

- Version catalog: `junit:junit` 4.13.2, `org.robolectric:robolectric` 4.17,
  `androidx.test:core` 1.7.0, `androidx.test.ext:junit` 1.3.0.
- `app/build.gradle.kts`: the above as `testImplementation`;
  `testOptions.unitTests.isIncludeAndroidResources = true`.
- `app/src/test/resources/robolectric.properties`: `sdk=36` (Robolectric 4.17 has no SDK 37
  image), English locale, fixed screen qualifiers.
- Robolectric starts the real `SyncthingApp`, so the Dagger graph is the production one.
- Done when a smoke test (`ApplicationContextTest`) passes under
  `IS_COPILOT=true ./gradlew :app:testDebugUnitTest`.

## Phase 2: characterization tests

Ordered by value for future refactors. Each item only uses public or package-visible API.

| Target | Kind | What it pins down |
|---|---|---|
| `util/Luhn` | JVM | Check characters for device ids |
| `settings/SettingsRoute.fromString` | JVM | Route names used by intents and shortcuts |
| `util/CertificateValidator` | JVM | Status per check for valid, expired, mismatched, wrong-key, garbage PEM (test-only fixtures generated with openssl, fake data) |
| `model/*` Gson parsing | JVM | Parsing of REST payloads (`Config`, `Folder`, `Device`, `Options`, `Connections`, `Event`, `SystemStatus`) from hand-written fixtures with fake ids |
| `util/ConfigXml` | Robolectric | Load fixture `config.xml` from `filesDir`, getters, add/update/remove folder and device, `saveChanges()` round-trip, GUI URL/port/API key |
| `util/ConfigRouter` | Robolectric | With `RestApi == null` it reads and writes `config.xml` |
| `util/Util`, `util/FileUtils` | JVM/Robolectric | Pure helpers (formatting, path handling) |
| `settings/SharedPreferenceFlow` | Robolectric | Pref changes flow out, writes go back to `SharedPreferences` |
| `service/RunConditionMonitor` | Robolectric | Should-run decision for the main matrix (Wi-Fi/mobile/metered/roaming/power/battery saver/flight mode), driven through shadows and preferences, observed via the public callback |

`generateConfig`, `SyncthingRunnable` and `RestApi` need the native binary or a live server and
are out of scope here.

## Phase 3: screenshot tests (Roborazzi on Robolectric)

- Roborazzi 1.76.0 (`roborazzi`, `roborazzi-compose`, `roborazzi-junit-rule`, Gradle plugin),
  Robolectric native graphics (`@GraphicsMode(NATIVE)`), fixed qualifiers (`w411dp-h891dp-xxhdpi`,
  `en`), light and dark where the screen supports it.
- Screens:
  - Every settings route, by launching `SettingsActivity` with `EXTRA_START_DESTINATION`.
  - Every onboarding page, by composing `OnboardingScreen` with a fixed `OnboardingUiState`
    (permissions granted / not granted).
  - The View-based screens that start without a running service: `MainActivity`, new folder and
    new device in `FolderActivity` / `DeviceActivity`, with a fixture `config.xml`.
- Golden images are committed in `app/src/test/screenshots/`.
- `./gradlew :app:recordRoborazziDebug` records, `:app:verifyRoborazziDebug` compares.

## Phase 4: detekt 2 + Compose Rules + ktfmt

- detekt `2.0.0-alpha.6` (`dev.detekt`), Compose Rules `0.6.8`, config copied from headroom
  (`config/detekt/detekt.yml`).
- Existing findings in `app/src/main` go into `app/detekt-baseline.xml`. This is the one deliberate
  exception to headroom's "no baselines" rule, because the owner asked not to touch app code yet.
  The baseline may only shrink.
- ktfmt `0.27.0` (`com.ncorti.ktfmt.gradle`), KotlinLang style. All test code is formatted. For
  `app/src/main`, the Kotlin files that are not ktfmt-clean today are listed in
  `config/ktfmt/excluded.txt`; every other file, and every new file, is checked. A file leaves the
  list when it is first touched by a refactor.
- `check` depends on detekt (all source sets) and `ktfmtCheck`.
- Java is covered by Lint only.

## Phase 5: gate and CI

- Local gate: `IS_COPILOT=true ./gradlew :app:check :app:verifyRoborazziDebug`.
- New workflow `.github/workflows/check.yaml` on pull requests and pushes to `main`: JDK 21,
  same gate. Upstream workflows are left as they are.

## Phase 6: docs and verification

- Update `AGENTS.md`, `docs/TESTING.md`, `docs/STATIC-ANALYSIS.md`.
- Verify the hard constraints: empty diff on `app/src/main`, identical runtime classpaths, full
  `assembleDebug`, gate green, screenshots verify twice in a row (determinism).
- Commit on branch `baseline` (Conventional Commits, one commit per phase), push to `origin`,
  open the PR against `tiwiz/syncthing-android:main`.

## Risks

| Risk | Mitigation |
|---|---|
| Robolectric does not support SDK 37 | Run on SDK 36 via `robolectric.properties` |
| detekt 2 alpha incompatible with Kotlin 2.4.20 / AGP 9 built-in Kotlin | headroom runs the same detekt on AGP 9.4.1; if it fails, pin detekt to run without type resolution, or stop and report |
| Compose test host activity needs a manifest entry | Prefer `roborazzi-compose`'s own host; if it needs `ui-test-manifest`, add it as `testImplementation` (not `debugImplementation`), so the APK manifest does not change |
| Screenshots differ between machines (fonts) | Record on Linux; if CI differs, CI only runs the tests and verification stays local |
| Robolectric starts `SyncthingApp`, which may touch files or StrictMode | Fine in tests; if a screen needs the service, skip it rather than change the app |

## Review notes (after a second pass)

- Order matters: the detekt baseline is generated for the `main` source set only and after the
  tests exist, so test code never hides behind it. ktfmt formats the tests before the baseline.
- `app/lint.xml` is also off limits: changing Lint behaviour for app code is a change to the app's
  gate, not a test addition.
- Coverage (Kover or JaCoCo) would show how dense the net is, but it is a follow-up, not part of
  this PR.

## Follow-ups (not in this PR)

1. Coverage report to find gaps before each refactor.
2. Shrink the detekt baseline and the ktfmt exclusion list as files are refactored.
3. Extract the run-condition decision into a pure function once the characterization tests exist.
