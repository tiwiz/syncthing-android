# Architecture

Syncthing-Fork is an Android wrapper around the Syncthing daemon written in Go ("SyncthingNative").
The app never syncs files itself. It starts the Go binary as a child process, talks to it over its
local REST API, and adds Android-specific behaviour around it: run conditions, notifications,
config editing, onboarding, Quick Settings tiles and a WebView for the Syncthing web GUI.

## Modules

| Module | Kind | Contents |
|---|---|---|
| `:app` | Android application (Java + Kotlin) | Everything that runs on the device: service, REST client, UI, settings |
| `:syncthing` | Gradle project with no code of its own | `buildNative` task that runs `syncthing/build-syncthing.py` |
| `syncthing/src/github.com/syncthing/syncthing` | Git submodule | Upstream Syncthing (Go), pinned to a tag (currently v2.1.7-rc.1) |

`:syncthing:buildNative` cross-compiles the Go code with the NDK for `armeabi-v7a`, `arm64-v8a`,
`x86` and `x86_64`, and writes `libsyncthingnative.so` into `app/src/main/jniLibs/<abi>/`
(git-ignored). It is not a real shared library: it is a PIE executable named `lib*.so` so that
the package manager extracts it to `nativeLibraryDir`, which is the only place Android still lets
an app execute files from. `useLegacyPackaging = true` in `app/build.gradle.kts` is what keeps it
extracted. `app/src/main/cpp/libSyncthingNative.mk` is an empty ndk-build stub so AGP packages the
ABIs.

All `merge*JniLibFolders` tasks depend on `:syncthing:buildNative`, unless the environment variable
`IS_COPILOT=true` is set. With it set, Java/Kotlin builds and Lint work without Go, but the APK
contains no binary and the service cannot start Syncthing.

Versions live in `gradle/libs.versions.toml`, which the Python build scripts and CI parse too:
`version-name` (`major.minor.patch.wrapper`, the first three follow Syncthing), `version-code`
(checked by the `validateAppVersionCode` task: `M*1_000_000 + m*10_000 + p*100 + w`),
`go_version`, `ndk-version`, `min-sdk` (30), `compile-sdk`/`target-sdk` (37).

## Package map (`com.nutomic.syncthingandroid`)

The namespace is inherited from the original app. The application id is
`com.github.catfriend1.syncthingfork` (`.debug` suffix for debug builds).

| Package | Language | Role |
|---|---|---|
| (root) | Kotlin | `SyncthingApp` (`@HiltAndroidApp`), `SyncthingModule` (Hilt module, `AppDependencies` entry point) |
| `service` | Kotlin | The core: `SyncthingService` (+ `ConfigBackup`, `HttpsCertFiles`), `SyncthingRunnable`, `RestApi` (+ `RestApiSupport`), `EventProcessor`, `RunConditionMonitor` (+ `RunConditionChecks`, `DeviceState`, `ObjectRunConditions`), `NotificationHandler`, Quick Settings tiles, `SyncTriggerJobService`, `Constants`, `AppPrefs` |
| `http` | Kotlin | Volley requests to the REST API: `ApiRequest`, `GetRequest`, `PostRequest`, `PollWebGuiAvailableTask`, `SyncthingTrustManager` |
| `model` | Kotlin | Gson models mirroring Syncthing's JSON (`Config`, `Folder`, `Device`, `Options`, `Gui`, `Event`, `FolderStatus`, `Connections`, …) plus local caches (`LocalCompletion`, `RemoteCompletion`, `CachedFolderStatus`) |
| `util` | Kotlin | `ConfigXml` (DOM read/write of `config.xml`), `ConfigRouter`, `FileUtils`/`SafUtils`/`FileOpener`, `PermissionUtil`, `Util` (shell), `TextFormat`, `UiUtil`, `JobUtils`, `CertificateValidator`, `extensions.kt` |
| `activities` | Kotlin, Compose | Every screen except onboarding, settings, recent changes and the web GUI: main (`MainContent`, `MainRows`, `MainDrawer`), folder (`FolderForm`, `FolderEditScreen`), device (`DeviceForm`, `DeviceEditScreen`), pickers and dialogs, share, Syncthing Camera, QR scanner, log, web page. `ThemedAppCompatActivity` applies the theme and edge-to-edge; `SyncthingActivity` binds the service |
| `fragments` | Kotlin, Compose | `DeviceIdDialogFragment` (Compose inside a dialog fragment) |
| `views` | Kotlin | `FolderRowState`, `DeviceRowState`: what a row of the main screen's lists shows |
| `onboarding` | Kotlin, Compose | Launcher activity: welcome, permissions, key generation |
| `settings` | Kotlin, Compose | `SettingsActivity` with Navigation 3 and one screen per section, backed by `SharedPreferenceFlow.kt` |
| `recentchanges` | Kotlin, Compose | List of recent disk events |
| `webgui` | Kotlin, Compose | `WebGuiActivity`, embeds the Syncthing web GUI in a WebView |
| `theme` | Kotlin, Compose | `ApplicationTheme` (Material 3) |
| `receiver` | Kotlin | `BootReceiver`, `AppConfigReceiver` (broadcast remote control) |

All app code is Kotlin and every screen is Jetpack Compose with Material 3 (migration plan:
[plans/KOTLIN-COMPOSE-MIGRATION.md](plans/KOTLIN-COMPOSE-MIGRATION.md)). The Syncthing camera
preview (zxing) and the WebViews are Views hosted in `AndroidView`.

## Dependency injection

Hilt (Dagger 2.60, KSP). `SyncthingModule` (`SingletonComponent`) provides the default
`SharedPreferences` and `NotificationHandler`. Activities and `SyncthingService` are
`@AndroidEntryPoint` and use field injection (`@Inject lateinit var`). Classes Hilt does not
create (`RestApi`, `EventProcessor`, `RunConditionMonitor`, `SyncthingRunnable`) are built by hand,
mostly inside `SyncthingService`, and get their dependencies in the constructor. Plain code with
only a `Context` (`ConfigXml`, `ConfigBackup`) uses `Context.appDependencies()`. Plan:
[docs/plans/HILT-VIEWMODELS.md](plans/HILT-VIEWMODELS.md).

## Runtime components

```
 UI (activities / Compose screens)
   │  bindService (SyncthingActivity) → SyncthingServiceBinder → SyncthingService
   │  getApi() → RestApi
   ▼
 SyncthingService (Service, foreground "specialUse")
   ├── RunConditionMonitor ── should-run decision ──┐
   ├── SyncthingRunnable (thread) ─ ProcessBuilder ─► libsyncthingnative.so serve --no-browser
   ├── PollWebGuiAvailableTask ─ polls until the GUI answers
   ├── RestApi ─ Volley over HTTPS to 127.0.0.1:<gui port>, X-API-Key
   ├── EventProcessor ─ long-polls /rest/events, updates RestApi caches and notifications
   ├── NotificationHandler ─ persistent + info channels
   └── ConfigRouter ─ RestApi when running, ConfigXml (config.xml on disk) when not
```

### `SyncthingService`

The central object. It is a started *and* bound service, kept in the foreground with a persistent
notification. Its `State` is `INIT → STARTING → ACTIVE → DISABLED`, or `ERROR`; changes go through
`onServiceStateChange()` under `mStateLock` and are broadcast to `OnServiceStateChangeListener`s on
the main thread (the UI uses this to enable or grey out screens).

- `onStartCommand` is also a command bus: `ACTION_RESTART`, `ACTION_STOP`,
  `ACTION_RESET_DATABASE`, `ACTION_RESET_DELTAS`, `ACTION_REFRESH_NETWORK_INFO`,
  `ACTION_IGNORE_DEVICE`, `ACTION_IGNORE_FOLDER`, `ACTION_OVERRIDE_CHANGES`,
  `ACTION_REVERT_LOCAL_CHANGES`. Notifications, adapters and settings screens send these intents.
- On the first start it creates the `RunConditionMonitor`. Whenever the monitor's decision flips,
  `onShouldRunDecisionChanged()` calls `launchStartupTask()` or `shutdown()`.
- `launchStartupTask()` loads `config.xml`, refuses to start if the GUI port is already taken
  (another instance, often another user profile), creates `RestApi`, kills any leftover native
  process, starts `SyncthingRunnable` on its own thread, and starts `PollWebGuiAvailableTask`.
  When the GUI answers, `RestApi.readConfigFromRestApi()` runs; when it completes,
  `onApiAvailable()` moves to `ACTIVE` and starts the `EventProcessor`.
- `shutdown()` defers itself while `STARTING`, then stops the poller, the event processor, calls
  `/rest/system/shutdown`, kills the process and joins the runnable thread.
- It also owns config import/export (zip with `config.xml`, keys and the app's
  `SharedPreferences`) and the custom HTTPS certificate swap with verify-and-rollback
  (`replaceHttpsCertificate`, `HttpsCertReplaceResult`).

Starters: `OnboardingActivity`/`MainActivity` (on app launch), `BootReceiver` (boot and package
replaced, if "start on boot" is on), `AppConfigReceiver` (broadcast intents `…action.START/STOP/FOLLOW`),
`SyncTriggerJobService` (scheduled sync windows), the Quick Settings tiles.

### `SyncthingRunnable`

Builds the command line (`serve --no-browser`, `generate`, `device-id`,
`debug reset-database`, `serve --debug-reset-delta-idxs`) and the environment
(`STHOMEDIR` = app files dir, `HOME`, `STNOUPGRADE`, `STMONITORED`, `STTRACE` from the debug
facilities preference, proxy and Tor settings, `GOGC`, `SQLITE_TMPDIR`, a gateway IP fallback for
Android 14+, and user-defined variables). It holds a Wi-Fi multicast lock for local discovery,
pipes stdout/stderr to logcat and to `syncthing.log` (trimmed on each start), and maps the exit
code: `3` means a restart was requested through the API, `0`/`137` are normal, others raise a crash
notification and stop the service.

`ConfigXml.generateConfig()` and the onboarding key generation use the same class with
`returnStdOut = true` for the one-shot commands.

### `RestApi` and `http`

`RestApi` is the stateful client. After startup it holds the whole `Config` (folders, devices,
options, GUI) read from `/rest/system/config`; edits change that copy and `sendConfig()` posts it
back. It also caches connections, completion per device and folder, folder status and the local
device id, and sends the `ACTION_NOTIFY_FOLDER_SYNC_COMPLETE` broadcast to whitelisted third-party
packages (see `wiki/developers/Integrate-your-app-with-Syncthing-receive-folder-sync-status.md`).
`applyCustomRunConditions()` pauses or resumes individual folders and devices according to their
per-object sync conditions.

Requests are Volley `StringRequest`s. `ApiRequest` always rewrites the host to `127.0.0.1`
(`forceLoopbackHost`), sends `X-API-Key`, disables hostname verification and uses
`SyncthingTrustManager`, which trusts the instance's own `https-cert.pem` plus the system and user
CAs. These relaxations are only safe because the target is loopback: do not change one without the
other. Callbacks arrive on the main thread.

Endpoints used: `/rest/system/{config,status,version,connections,discovery,loglevels,shutdown}`,
`/rest/db/{completion,ignores,override,revert,scan,status}`, `/rest/events`, `/rest/events/disk`,
`/rest/cluster/pending/{devices,folders}`, `/rest/stats/device`, `/rest/svc/{deviceid,report}`,
`/rest/debug/support`.

### `EventProcessor`

A `Runnable` scheduled every 5 seconds on the main-thread `Handler` while the service is `ACTIVE`.
It reads `/rest/events?since=<lastId>` and dispatches: pending devices and folders become consent
notifications; `FolderCompletion`, `FolderSummary`, `StateChanged`, `ItemFinished`,
`LocalIndexUpdated`, `RemoteIndexUpdated`, `DeviceConnected/Disconnected/Paused/Resumed`,
`FolderPaused/Resumed`, `FolderErrors` update the caches in `RestApi`. `ItemFinished` also tells the
MediaStore about added or deleted files, so galleries pick them up. The last event id is persisted
in preferences.

### `RunConditionMonitor`

Decides whether Syncthing should run. It listens to battery, power-save, network and Android sync
setting changes, plus `ACTION_UPDATE_SHOULDRUN_DECISION`, and evaluates `decideShouldRun()` from the
preferences: run on Wi-Fi / mobile data / roaming / metered Wi-Fi, a Wi-Fi SSID whitelist (needs
location permission), power source, respect battery saver, respect Android auto-sync, flight mode
and the time-window schedule (`SyncTriggerJobService` + `JobUtils`). The result goes to
`SyncthingService` (start/stop) and `RestApi.applyCustomRunConditions` (per folder/device).
`getRunDecisionExplanation()` is the text shown to the user.

### Configuration: `ConfigXml` and `ConfigRouter`

Syncthing's `config.xml`, keys, certificate and database live in the app's files dir. When
Syncthing is not running, `ConfigXml` reads and edits `config.xml` directly with the DOM parser
(and `saveChanges()` writes it back). On first run `generateConfig()` runs `syncthing generate`,
then sets the GUI user to `syncthing` with the API key as password (bcrypt), applies Android-friendly
defaults (trash-can versioning for new folders, …) and, in debug builds only, moves the GUI to
`127.0.0.1:8385` and the sync port to `22001` so a debug and a release install can run side by side.
The release GUI port is `8384` (`Constants.DEFAULT_WEBGUI_TCP_PORT`).

`ConfigRouter` hides the two cases from the UI: every getter/setter takes a possibly null
`RestApi` and goes to the REST API when it is loaded, to `ConfigXml` otherwise. Folder and device
screens therefore work while Syncthing is stopped.

### Preferences

App settings are the default `SharedPreferences`. The keys are the `PREF_*` constants in
`service/Constants.kt`. Per-folder and per-device sync conditions use prefixed keys built by
`Constants.DYN_PREF_OBJECT_*`. The Compose settings screens observe them through
`createPreferenceFlow()` (`settings/SharedPreferenceFlow.kt`) and `zhanghai` compose-preference.
The import/export zip includes these preferences, so renaming a key breaks old backups.

### Notifications

`NotificationHandler` owns three channels: persistent (service running), persistent-waiting
(service waiting for run conditions) and info (crashes, consent requests for new devices and
folders, revoked storage permission, restart required). Consent notifications carry
`SyncthingService` intents for accept/ignore.

## UI flow

1. `OnboardingActivity` is the launcher. It shows the welcome, storage, battery optimisation,
   location, local-network (Android 17) and notification pages as needed, then generates keys and
   `config.xml` (`KeyGenerationPage`). When everything is in place it skips straight to
   `MainActivity` (or `WebGuiActivity` when "start into web GUI" is on).
2. `MainActivity` starts the service and shows `MainContent`: folders, devices and status as
   destinations of a `NavigationSuiteScaffold` (navigation bar on phones, rail on wider windows),
   with the settings icon and the overflow menu (`MainMenu`) in the top bar.
3. Detail and edit screens (`FolderActivity`, `DeviceActivity` and the dialog activities) extend
   `SyncthingActivity`, which binds the service in `onResume` and unbinds in `onPause`, and use
   `ConfigRouter` to read and save.
4. `SettingsActivity` hosts the Compose settings with a Navigation 3 back stack
   (`SettingsRoute`), and calls `evaluateRunConditions()` on stop.
5. Other entry points: `ShareActivity` (share files into a synced folder), `PhotoShootActivity`
   (own launcher icon, takes photos into a synced folder), `QRScannerActivity`, `LogActivity`,
   `RecentChangesActivity`, `WebGuiActivity`.

## Threading

There are no coroutines in the service layer. The main thread runs the service callbacks, Volley
callbacks and the `EventProcessor`. `SyncthingRunnable` runs on a dedicated thread with two log
reader threads. `RestApi` has a single-thread executor for blocking work. Shared state uses
`synchronized` blocks (`mStateLock`, `mConfigLock`, `mAsyncQueryCompleteLock`). The Compose code
uses `lifecycleScope`/`rememberCoroutineScope` and `Flow` for preferences.

## External interfaces

- Broadcast remote control: `${applicationId}.action.START|STOP|FOLLOW` to `AppConfigReceiver`
  (`wiki/tips-and-tricks/Remote-Control-by-Broadcast-Intents.md`).
- Sync-complete broadcast to whitelisted apps, guarded by
  `${applicationId}.permission.RECEIVE_SYNC_STATUS`.
- Quick Settings tiles: force start/stop, schedule, camera.
- The Syncthing web GUI on loopback, optionally on all interfaces, with an optional custom
  certificate (`SettingsCustomCertificateScreen`, `CertificateValidator`).

## Build and release pipeline

- `.github/workflows/build-app.yaml`: on every PR and push to `main`, `common-build.yaml` installs
  Go (pinned), the NDK, builds the native libraries (cached per Syncthing commit), runs Lint and
  assembles the debug APK; `common-sign.yaml` signs it.
- `release-app.yaml`: release build, signing, GitHub release, Play/F-Droid metadata in
  `app/src/main/play/`.
- `update-syncthing-submodule.yaml` and `update-go-version.yaml` open PRs that bump the submodule
  and `go_version`. Dependabot bumps Gradle dependencies and actions.
- CI runs `./gradlew buildNative lint<Type> assemble<Type>`; Lint has `abortOnError = true`, so any
  Lint error fails the build. There are no unit or instrumented tests.
- The changelog is maintained by the upstream maintainer at release time (`chore(readme): update
  changelog` commits); do not edit it.
