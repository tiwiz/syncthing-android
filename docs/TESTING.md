# Testing

## TDD flow

Adapted from [rock3r/headroom](https://github.com/rock3r/headroom/blob/main/docs/TESTING.md).

1. Write the test first.
2. Run just that test and check it fails for the reason you expect.
3. Write the minimum production code to make it pass.
4. Run the test again, then the gate.
5. Refactor with the tests green.

## Refactoring safety net

Before changing app code, the gate must be green, and it must stay green after the change without
re-recording anything. A golden file or screenshot that changes is a behaviour change: either it is
the point of the task (re-record, review the diff, explain it in the commit), or it is a regression.

```bash
IS_COPILOT=true ./gradlew :app:check :app:verifyRoborazziDebug   # the gate (~2 min warm)
```

## Test layers

Almost all tests live in `app/src/test` and run on the JVM; only the WebView screens also have
instrumented tests (below). Robolectric runs SDK 36, because 4.17 has no
SDK 37 image; the app targets 37.

| Layer | Runner | Examples |
|---|---|---|
| Plain JVM | JUnit 4 | `LuhnTest`, `CertificateValidatorTest`, `RestModelParsingTest` |
| Android logic | Robolectric | `ConfigXmlTest`, `ConfigRouterTest`, `RunConditionMonitorTest`, `DeviceTest`, `UtilTest`, `BootReceiverTest`, `AppConfigReceiverTest` |
| REST client | Robolectric + `FakeSyncthing` (MockWebServer) | `RestApiTest`, `EventProcessorTest` |
| Screens driven by hand | Robolectric (+ Compose test for onboarding) | `WebGuiActivityTest`, `WebViewActivityTest`, `EditScreensTest`, `FolderDialogsTest`, `QrScanTest`, `ShareActivityTest`, `FolderPickerActivityTest`, `OnboardingFlowTest`, `VersioningDialogActivityTest`, `SettingsNavigationTest`, `QuickSettingsTilesTest` |
| Native process, service lifecycle | Robolectric + `FakeSyncthingBinary` (a `/bin/sh` script) | `SyncthingRunnableTest`, `SyncthingServiceLifecycleTest` |
| Screenshots | Robolectric + Roborazzi, native graphics | `SettingsScreenshotTest`, `OnboardingScreenshotTest`, `ActivityScreenshotTest`, `RunningScreenshotTest`, `WindowSizeScreenshotTest`, `ComponentGalleryScreenshotTest`, `OtherScreensScreenshotTest`, `VersioningScreenshotTest`, `WebGuiScreenshotTest` |
| WebView screens on a device | AndroidJUnitRunner + Espresso-Web, HTTPS MockWebServer | `WebGuiActivityWebTest`, `WebViewActivityWebTest` |

```bash
IS_COPILOT=true ./gradlew :app:testDebugUnitTest                         # unit tests only
IS_COPILOT=true ./gradlew :app:testDebugUnitTest --tests '*ConfigXmlTest' # one class
IS_COPILOT=true ./gradlew :app:verifyRoborazziDebug                      # tests + compare screenshots
```

## Instrumented tests (WebView)

Robolectric's `ShadowWebView` records what the activities ask of the WebView (URL, headers, client
callbacks), so `WebGuiActivityTest` and `WebViewActivityTest` call the `WebViewClient` callbacks by
hand. Real page loads, certificate handling by Chromium, links and history run on a device in
`app/src/androidTest` with Espresso-Web:

```bash
# On an emulator only: the run installs the debug app and uninstalls it afterwards, with its data.
emulator -avd <avd> -no-window -no-audio &      # API 35 google_apis x86_64 in CI
adb shell settings put global window_animation_scale 0     # also transition_/animator_duration_scale
ANDROID_SERIAL=emulator-5554 IS_COPILOT=true ./gradlew :app:connectedDebugAndroidTest
```

- CI runs them in the `Instrumented tests` job of `check.yaml` (android-emulator-runner).
- Always set `ANDROID_SERIAL` when a phone is plugged in, or Gradle runs (and uninstalls) on it too.
- The debug APK is signed with the SDK debug key only when an `AndroidTest` task is requested.
- Pages come from an HTTPS `MockWebServer` on 127.0.0.1 with a self-signed certificate
  (`WebTestSupport.kt`). For the web GUI, the test writes the fixture `config.xml` with the
  server's port and pins its certificate in `https-cert.pem`. Syncthing is kept from starting
  (force stop; without the storage permission the service stops itself anyway), and the test
  reports the service ACTIVE through `onServiceStateChange`, as `RunningSyncthingService` does.
- Espresso does not wait for network loads: wrap checks in `eventually { }`.
- Click links with a real tap (`onView(withId(...)).perform(click())`), not `webClick()`: Chromium
  skips history entries left without a user gesture, so back would close the screen.
- `app/src/androidTest/AndroidManifest.xml` removes `androidx.startup.InitializationProvider` from
  the test APK: its classes are only in the app APK, and the crash dialog stole the window focus.

## Characterization tests and golden files

Most tests pin what the app does today, not what it should do. Expected output is stored in
`app/src/test/resources/golden/` and compared by `Golden.assertMatches()`.

- Re-record after an intended change: `IS_COPILOT=true ./gradlew :app:testDebugUnitTest -Pgolden.record=true`,
  then review `git diff app/src/test/resources/golden`.
- A test commented `// CHARACTERIZATION:` documents a known oddity or bug (listed in
  [TECH-DEBT.md](TECH-DEBT.md)). Fixing it is a separate change that updates that test on purpose.
- Saved `config.xml` files are produced by the JVM's XML transformer, which formats differently from
  Android's. They pin behaviour on the JVM, not byte-for-byte output on a device.

## The fake Syncthing REST API

`testutil/FakeSyncthing` serves the REST fixtures over HTTPS on `127.0.0.1` with MockWebServer. Its
self-signed certificate is written to `Constants.getHttpsCertFile`, so requests go through the real
Volley stack, `SyncthingTrustManager` pinning and loopback rewrite.

- `respond(method, path, body, code)` overrides one endpoint; `requests(path, method)` lists what the
  app sent. Unknown GETs answer 404, POSTs answer 200.
- Volley delivers on the main looper: wait with `awaitMain { … }`, and use `drainMain()` before
  checking that something did *not* happen. Delayed work (`postDelayed`) needs
  `shadowOf(getMainLooper()).idleFor(…)`.
- Tests that reach `RestApi.shutdown()` must use `ShadowUtilDeviceShell` (with
  `instrumentedPackages = ["com.nutomic.syncthingandroid.util"]`): it calls `Util.killProcess("find")`,
  which would otherwise kill `find` processes on the host.
- Volley still links `org.apache.http`, which Android ships and Robolectric does not; the test
  classpath adds `httpclient` for it.

## The fake native binary

`testutil/FakeSyncthingBinary` points `nativeLibraryDir` at a temporary folder and writes a `/bin/sh`
script named `libsyncthingnative.so`. It records its arguments and environment, prints the given
stdout/stderr and exits with the given code. With `generatedConfig`, `generate` writes it as
`$STHOMEDIR/config.xml` (used by `OnboardingFlowTest`). With `serveUntilStopped`, `serve` keeps running until
`stop()`; `SyncthingServiceLifecycleTest` calls it from `FakeSyncthing.onShutdown`, so the REST
shutdown ends the process as it would on a device.

- The service polls the GUI with `postDelayed`: wait with a loop that runs the looper clock
  (`idleFor`), not with `awaitMain`, which does not.
- Create `FakeSyncthing(…, resetVolley = false)` after the service started polling, and reset the
  Volley queue yourself before starting the service.
- `ShadowUtilDeviceShell.netstatOutput` fakes `netstat` for the "GUI port already in use" check.

## Screenshots

- Golden images: `app/src/test/screenshots/<group>/<name>[-dark].png`, 1233×2673
  (`w411dp-h891dp-xxhdpi`, English).
- Record: `IS_COPILOT=true ./gradlew :app:recordRoborazziDebug`. Compare: `:app:verifyRoborazziDebug`.
  A failed comparison writes `*_actual.png` and `*_compare.png` next to the golden (git-ignored).
- Most screens run with the fixture config and a real `SyncthingService` that was created but not
  started (`StoppedSyncthingService`), so they show the "Syncthing not running" state.
- `RunningScreenshotTest` (`screenshots/running/`) shows the main tabs, folder and device screens and
  some settings with Syncthing running: `RunningSyncthingService` sets the service to ACTIVE with a
  `RestApi` loaded from `FakeSyncthing`. It pins the time zone to UTC for "last seen" times.
  `RunningScreenRecorder` (`RunningScreens.kt`) opens and captures these screens for both tests.
- `WindowSizeScreenshotTest` (`screenshots/windows/`): main tabs, folder and device screens at
  each window size class the adaptive layouts target: compact 411×891, medium 700×960, expanded
  840×900 (unfolded foldable), large 1280×800 (tablet landscape), at mdpi, in English and, for
  compact and large, Italian. Light theme only.
- `OtherScreensScreenshotTest` (`screenshots/other/`): log (fake logcat from the shell shadow), sync
  conditions, photo shoot, share, recent changes (exact times) and the device id dialog, with
  Syncthing running.
- `WebGuiScreenshotTest` (`screenshots/webgui/`): the web GUI screen's load error. The loading
  spinner never stops moving, and web content is left to the instrumented tests.
- Anything non-deterministic is pinned in the test, never in the app: random folder ids, text
  cursors, async loading (`settle()`).

## Fixtures

`app/src/test/resources/fixtures/` (see its README):

- `syncthing/`: `config.xml` and REST responses captured from a throwaway Syncthing v2.1.7 instance
  and anonymised. To refresh them, build the submodule for the host
  (`go build ./cmd/syncthing`), run it with a temporary `--home`, and replace ids, keys, paths and
  IPs with the same fake values.
- `certs/`: OpenSSL-generated certificates and keys for `CertificateValidator`. They are fake and
  protect nothing.

## Test-only shadows

`testutil/` provides shadows for what Robolectric 4.17 lacks on SDK 36, or for host-dependent code:

- `ShadowContentResolverWithStatusListeners`: sync status listeners (`RunConditionMonitor`).
- `ShadowEnvironmentWithAllFilesAccess`: `Environment.isExternalStorageManager()` (`SyncthingService`).
- `ShadowUtilDeviceShell`: `Util.runShellCommandGetOutput` answers like an Android shell instead of
  running the host's `sh` (the About screen shows `ulimit -n` and `du`, which differ per machine).
  It needs `instrumentedPackages = ["com.nutomic.syncthingandroid.util"]` in `@Config`.

## Coverage

JaCoCo, built into AGP, measures line coverage of the unit tests. It is off by default, so the gate
runs the same bytecode as the app; `-Pcoverage=true` turns it on for the debug unit tests.

```bash
IS_COPILOT=true ./gradlew -Pcoverage=true :app:verifyRoborazziDebug :app:createDebugUnitTestCoverageReport
# → app/build/reports/coverage/test/debug/index.html (and report.xml)
```

- Run it through `verifyRoborazziDebug`, not plain `testDebugUnitTest`: without a Roborazzi mode,
  `captureRoboImage { }` skips the composable and the onboarding screens look uncovered.
- Robolectric loads app classes through its own class loader, so the test task sets JaCoCo's
  `includeNoLocationClasses`; without it the report is almost empty.
- Look at the report before refactoring a class: code that no test runs is not protected.

Snapshot on 2026-10-13 (lines): 66% overall. `receiver` 94%, `model` 87%, `http` 79%,
`fragments/dialog` 77%, `recentchanges` 77%, `util` 74%, `fragments` 72%, `service` 71%,
`onboarding` 71–72%, `settings` 59%, `views` 55%, `activities` 51%; `webgui` 10% (WebView).

## Not covered yet

- The web GUI's own JavaScript UI (served by the real Syncthing) is not exercised; the instrumented
  tests use a fake page.
- Syncthing Camera's failure path (picture cancelled or unreadable): Robolectric's MediaStore
  shadow decodes any picture, so only the success path is tested.
- The QR scanner's camera preview: Robolectric has no camera, so `QrScanTest` reports a scan to the
  scanner's callback directly.
