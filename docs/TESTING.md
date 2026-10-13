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

All tests live in `app/src/test` and run on the JVM. Robolectric runs SDK 36, because 4.17 has no
SDK 37 image; the app targets 37.

| Layer | Runner | Examples |
|---|---|---|
| Plain JVM | JUnit 4 | `LuhnTest`, `CertificateValidatorTest`, `RestModelParsingTest` |
| Android logic | Robolectric | `ConfigXmlTest`, `ConfigRouterTest`, `RunConditionMonitorTest`, `DeviceTest`, `UtilTest` |
| Screenshots | Robolectric + Roborazzi, native graphics | `SettingsScreenshotTest`, `OnboardingScreenshotTest`, `ActivityScreenshotTest` |

```bash
IS_COPILOT=true ./gradlew :app:testDebugUnitTest                         # unit tests only
IS_COPILOT=true ./gradlew :app:testDebugUnitTest --tests '*ConfigXmlTest' # one class
IS_COPILOT=true ./gradlew :app:verifyRoborazziDebug                      # tests + compare screenshots
```

There are no instrumented tests (`src/androidTest`).

## Characterization tests and golden files

Most tests pin what the app does today, not what it should do. Expected output is stored in
`app/src/test/resources/golden/` and compared by `Golden.assertMatches()`.

- Re-record after an intended change: `IS_COPILOT=true ./gradlew :app:testDebugUnitTest -Pgolden.record=true`,
  then review `git diff app/src/test/resources/golden`.
- A test commented `// CHARACTERIZATION:` documents a known oddity or bug (for example
  `Util.readableFileSize` crashing at 1 PiB, `ConfigXml.removeDevice` leaving the device in its
  folders). Fixing it is a separate change that updates that test on purpose.
- Saved `config.xml` files are produced by the JVM's XML transformer, which formats differently from
  Android's. They pin behaviour on the JVM, not byte-for-byte output on a device.

## Screenshots

- Golden images: `app/src/test/screenshots/<group>/<name>[-dark].png`, 1233×2673
  (`w411dp-h891dp-xxhdpi`, English).
- Record: `IS_COPILOT=true ./gradlew :app:recordRoborazziDebug`. Compare: `:app:verifyRoborazziDebug`.
  A failed comparison writes `*_actual.png` and `*_compare.png` next to the golden (git-ignored).
- Screens run with the fixture config and a real `SyncthingService` that was created but not started
  (`StoppedSyncthingService`), so they show the "Syncthing not running" state.
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

Snapshot on 2026-10-13 (lines): 37% overall. `util` 69%, `settings` 57%, `onboarding` 50–68%,
`model` 47%, `fragments` 36%, `activities` 24%, `service` 16%, `views` 11%; `http`, `webgui`,
`recentchanges` and `fragments/dialog` 0%.

## Not covered yet

- `RestApi` (0%), `EventProcessor` (0%), `SyncthingRunnable` (0%), `SyncthingService` start/stop
  (6%) and the `http` requests (0%) need a running Syncthing or a fake HTTP server.
- `ShareActivity`, `LogActivity`, `FolderPickerActivity`, `PhotoShootActivity`,
  `SyncConditionsActivity`, `WebViewActivity`, `WebGuiActivity`, recent changes and the device id
  dialog have no test at all.
- Screens are only captured with Syncthing stopped, and no test clicks anything.
