# Hilt and ViewModels

Asked by the owner on 2026-10-16: move dependency injection from hand-written Dagger to Hilt, and
move every piece of screen state into ViewModels, so it survives configuration changes (rotation,
folding and unfolding, window resizing, theme, locale and font size changes).

Read with [M3-EXPRESSIVE-HANDOFF.md](M3-EXPRESSIVE-HANDOFF.md): same way of working, same split
between agent A and agent B, same rules (TDD, gate, one topic per PR).

## Where things stand

- **Dagger 2.60.1 by hand.** `DaggerComponent` has one `inject()` per class (14):
  - activities: Main, Folder, Device, Onboarding, PhotoShoot, Share, SyncConditions, Settings;
  - the service and the app;
  - plain classes that call `component().inject(this)` themselves: `RestApi`, `EventProcessor`,
    `RunConditionMonitor`, `SyncthingRunnable`.

  `SyncthingModule` provides `SharedPreferences` and `NotificationHandler`.
- **No ViewModel anywhere.** Each screen's state lives in its Activity, held in `mutableStateOf`:
  - Folder and device screens keep it in `FolderEditController` and `DeviceEditController`.
  - The main screen's detail pane keeps it in `DetailPanes`.
  - It survives recreation only through `onSaveInstanceState`: Gson JSON in a `Bundle`, used by
    folder, device and onboarding. Everything else is reloaded, or lost.

  UI-only state uses `rememberSaveable`, in 19 files.
- **The service is bound per Activity.** `SyncthingActivity` binds in `onResume` and unbinds in
  `onPause`, and `OnServiceStateChangeListener` pushes the state. Settings screens get the service
  as a `SyncthingServiceState` parameter. Refresh loops run in the Activity
  (`MainActivity.startRefreshing`).

## Target

- **Hilt.**
  - Application and entry points:
    - `@HiltAndroidApp SyncthingApp`;
    - `@AndroidEntryPoint` on activities, `SyncthingService` and the tiles;
    - `SyncthingModule` becomes `@InstallIn(SingletonComponent::class)`.
  - Plain classes get constructor injection, or receive their dependencies from whoever builds
    them; `@AssistedInject` where they also need runtime arguments.
  - `DaggerComponent` and every `inject()` call go.
- **`SyncthingConnection`** (`@Singleton`): binds `SyncthingService` with the application context
  and exposes `StateFlow<ServiceSnapshot>` (state, `RestApi?`).
  - It stays bound only while someone collects it (`stateIn(WhileSubscribed(5_000))`), so exit and
    the service's own lifecycle keep working.
  - It replaces `SyncthingActivity`'s binding and the listener plumbing for the UI.
- **One `@HiltViewModel` per screen.**
  - Each exposes its UI state as `StateFlow`, collected with `collectAsStateWithLifecycle`.
  - Arguments and anything that must survive process death go in `SavedStateHandle` (the forms:
    `@Parcelize` or JSON).
  - Refresh loops run in `viewModelScope`.
- **One-off effects** (open a screen for a result, close, show a toast) go from the ViewModel as
  events (`Channel` received as a `Flow`). The UI layer runs them:
  - activity results through `rememberLauncherForActivityResult`;
  - the result handed back to the ViewModel.

  `FolderEditHost` and `DeviceEditHost` go away.
- **Screens stay stateless:** state in, actions out. Only the route-level composable (or the
  Activity) gets the ViewModel (Compose Rules: `ViewModelForwarding`, `ViewModelInjection`).
  UI-only state (menu open, scroll, a text field being typed) may stay in `rememberSaveable`.
- **Navigation 3 settings:** ViewModels scoped to their `NavEntry` (the navigation3 ViewModel
  entry decorator), so leaving a category clears its state.

## Dependencies (to verify in H1)

- `com.google.dagger:hilt-android` and `hilt-compiler` 2.60.1 (KSP), plus the
  `com.google.dagger.hilt.android` Gradle plugin. Check compatibility with AGP 9.4 and KSP 2.3
  before anything else (spike).
- `androidx.hilt:hilt-lifecycle-viewmodel-compose` 1.4.0 (`hiltViewModel()`).
- `androidx.lifecycle:lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, and the
  navigation3 ViewModel decorator (`lifecycle-viewmodel-navigation3`).
- Tests: `kotlinx-coroutines-test` (`Dispatchers.setMain`). Add `hilt-android-testing` only if a
  test needs to replace a binding; otherwise Robolectric runs the real `@HiltAndroidApp`.

## Steps

One PR each, with the gate green. Behaviour tests (drivers) stay unchanged.

- **H1** `build: hilt instead of hand-written dagger` (A):
  - plugin and dependencies;
  - `@HiltAndroidApp`, `@AndroidEntryPoint` and the module as above;
  - field injection stays where it is (`@Inject lateinit var`) and `DaggerComponent` goes;
  - no behaviour change, every test green.

  Done: Hilt 2.60.1 (plugin and `hilt-compiler` with KSP work with AGP 9.4); `SyncthingModule` is
  an `object` in `SingletonComponent`; `AppDependencies` (`@EntryPoint`, `Context.appDependencies()`)
  serves the four plain classes until H2; `ApplicationContextTest` checks the Hilt graph. All 615
  unit tests pass with the real `@HiltAndroidApp` under Robolectric, no test application needed.
- **H2** `refactor(service): plain classes get their dependencies, not the component` (A):
  - `RestApi`, `EventProcessor`, `RunConditionMonitor` and `SyncthingRunnable` take them in their
    constructors from `SyncthingService` (injected);
  - tests build them with fakes;
  - no `inject(this)` left.

  Done: the four classes take `SharedPreferences` / `NotificationHandler` in their constructors;
  `SyncthingService` passes its injected ones; only `ConfigXml` and `ConfigBackup` (plain classes
  with a `Context`) and test code reach the graph, through `Context.appDependencies()`.
- **H3** `feat(service): SyncthingConnection` (A), tests first:
  - binds while collected, unbinds after the timeout;
  - reports state changes and the API;
  - exit still stops the service.

  Not yet used by screens.

  Done: `service/SyncthingConnection.kt` (`ServiceSnapshot`, `snapshot: StateFlow`, binds through
  a `callbackFlow`, `WhileSubscribed(5 s)`), `@ApplicationScope` scope from `SyncthingModule`,
  `kotlinx-coroutines-test` 1.9.0, `SyncthingConnectionTest` (4).
- **H4** `refactor(main): MainViewModel` (A):
  - `uiState`, the refresh loop and usage reporting move out of `MainActivity`;
  - the detail pane stays in `DetailPanes` until H5 (its controllers become ViewModels there);
  - `MainActivity` collects.

  New test: `scenario.recreate()` keeps the usage reporting question without asking Syncthing
  again.

  Done: `MainViewModel` (`@HiltViewModel`; observes `SyncthingConnection`; `uiState`,
  `refreshWhileShown()` run by the activity while resumed, usage reporting asked once per
  ViewModel, force start/stop; a `FINISH` event when Syncthing crashes). `MainActivity` keeps the
  menu, the detail panes, navigation and exit. `lifecycle-runtime-compose` 2.10.0.
- **H5** `refactor(folder, device): FolderEditViewModel, DeviceEditViewModel` (A):
  - the controllers become ViewModels with `SavedStateHandle` (Gson `Bundle` state gone);
  - activity results go through events and `rememberLauncherForActivityResult`;
  - the same ViewModels serve the activities and the detail pane; the selection moves from
    `DetailPanes` to `MainViewModel`'s `SavedStateHandle`, and `DetailPanes` goes;
  - recreation tests for both.

  Done, adapted: the tested controllers stay as the screens' logic; `FolderEditViewModel` and
  `DeviceEditViewModel` (`EditViewModels.kt`, assisted injection with the screen's args) hold
  them, act as their host (requests become `EditEvent`s) and save them through
  `SavedStateHandle.setSavedStateProvider`. `EditEffects` runs the events with
  `rememberLauncherForActivityResult`, in the activities and in the detail pane alike;
  `FolderActivity`/`DeviceActivity` no longer implement the hosts nor save state. `DetailPanes`
  keeps the selection (saved instance state) and gets each opening's ViewModels from the
  activity's store, keyed `<key>#<n>`. Recreation tests: folder, device, detail pane.
- **H6** `refactor(onboarding): OnboardingViewModel` (B): the JSON `uiState` in the `Bundle`
  becomes ViewModel and `SavedStateHandle` state; recreation test.
- **H7** `refactor(settings): ViewModels per category` (B): one per screen with state, scoped to
  its `NavEntry`; the service comes from `SyncthingConnection`, not a parameter; recreation tests
  on the screens with dialogs and forms (custom certificate, import/export, run conditions).
- **H8** `refactor: ViewModels for the remaining screens` (B; split if large):
  - share, photo shoot, QR scanner, log, recent changes, folder picker, sync conditions;
  - versioning and single-choice dialogs, only where they have more than UI-only state;
  - web GUI and web page, for their loading and certificate state;
  - recreation tests.
- **H9** `refactor: drop the per-activity service binding` (A):
  - `SyncthingActivity` binding and `OnServiceStateChangeListener` for the UI go;
  - the tiles use `SyncthingConnection`;
  - ARCHITECTURE.md, CONVENTIONS.md (new rule: screen state in ViewModels) and TESTING.md
    (recreation tests) updated.

## Tests

- **ViewModel unit tests** with a fake `SyncthingConnection` and `kotlinx-coroutines-test`; plain
  JUnit where no Android type is involved, Robolectric otherwise.
- **Recreation tests**, one per screen with state: change something, `scenario.recreate()`,
  check it is still there (unsaved form, selected tab or detail, open dialog, progress of an
  import).
- Process death: `SavedStateHandle` round trip, via `ActivityScenario.recreate()` with a fresh
  ViewModel store, or by testing the handle directly.
- Screenshots and golden files do not change in these PRs: they are refactors.

## Order with the Material 3 Expressive plan

- **A:** 8c (open) → 9 → H1 → H2 → H3 → H4 → H5 → 12 → H9 → 13.
- **B:** 10 → 11 → (after H3 is merged) H6 → H7 → H8.
- H1–H3 change shared infrastructure (`app/build.gradle.kts`, `gradle/libs.versions.toml`,
  `SyncthingApp`, `service/`): A announces them, B rebases after each.
- A screen moves to a ViewModel after its Expressive restyle, never in the same PR.
- Milestone 13 checks both plans: walk every screen, rotate and fold/unfold with unsaved changes.

## Risks

- **Hilt with AGP 9.4 and KSP 2.3:** if the Gradle plugin is not compatible yet, H1 stops and the
  owner decides (wait, or keep Dagger and add ViewModels with a hand-written factory).
- **Service lifetime:** an app-scoped binding must not keep `SyncthingService` alive after exit.
  `WhileSubscribed` plus explicit tests on exit and on `ACTION_EXIT` cover it.
- **Robolectric with Hilt:** entry points need the Hilt app as the test application; the
  `testutil` shadows and `StoppedSyncthingService` / `RunningSyncthingService` must keep working,
  or be replaced by a fake `SyncthingConnection` binding (`@TestInstallIn`).
- **`TileService`s:** they are short-lived; check Hilt injection there, or keep them on
  `EntryPointAccessors`.
- **State size in `SavedStateHandle`:** forms are small; never put lists of folders or the config
  there (reload them instead).
