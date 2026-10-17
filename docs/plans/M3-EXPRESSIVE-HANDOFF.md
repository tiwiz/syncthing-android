# Material 3 Expressive: handoff and remaining plan

Brief for any agent joining the Material 3 Expressive work. Read [AGENTS.md](../../AGENTS.md)
first (non-negotiables, build, gate), then [M3-EXPRESSIVE.md](M3-EXPRESSIVE.md) (decisions and
the 13 steps), then this file: where things stand, how the UI code is built, how to work, and a
detailed plan for steps 8–13 with a split for two agents.

Design canvas (approved by the owner): <https://claude.ai/artifact/BzMQAgmqaUHNL16zdTXfvf>.

## Where things stand (2026-10-16)

Done and merged on `main`, one PR per step, every PR green in CI:

| Step | PR | What |
|---|---|---|
| — | #64 | minSdk 30; code for older Android removed |
| 1 | #65 | `MaterialExpressiveTheme`, fallback colors, Roboto Flex, material3 1.5.0-beta01, Compose UI 1.12.1 |
| 2 | #66 | `WindowSizeScreenshotTest`: main, folder, device screens at 4 window sizes + Italian |
| 3 | #67 | `ui/` components, `views/StateTone` |
| 4 | #68 | `NavigationSuiteScaffold` (bar / rail / expanded rail), overflow menu instead of the drawer |
| 5 | #69 | Folder list: title, total progress card, grouped rows, add FAB menu; exit FAB removed |
| 6 | #70 | Device list in the same style |
| 7 | #71 | Status tab: hero, connected run mode group (Automatic / Run / Stop), conditions, figure tiles |

The owner tests the main screen on the Pixel 8 before step 8 starts. Wait for their feedback
before merging step 8 PRs; preparatory refactors (8a, 8b) can be opened earlier.

Left: steps 8–13, about 12–15 PRs (detail below).

### Workload on 2026-10-17

| Agent | Done | Left, in order |
|---|---|---|
| A | 8a #73, 8b #74, 8c #76, 9; H1 #78, H2 #79, H3 #81, H4 #83, H5 #84 | 12 → H9 (needs B's H6–H8) → 13 with the owner |
| B | 10a #77, 10b #80, 10c #82 | 10d (log, recent changes, device ID dialog, web top bars: started, uncommitted in `.slim/worktrees/m3e-b`) → 10e → 11a → 11b → H6 → H7 → H8 |

Updated later on 2026-10-17: B also merged 10d #85, 10e #87, 11a #88 and 11b #89, and opened
step 12 as #90 (A's step). Steps 10 and 11 are done.

| Agent | Done | Left, in order |
|---|---|---|
| A | 8a #73, 8b #74, 8c #76, 9 #86, 12 #90 (finished); H1 #78, H2 #79, H3 #81, H4 #83, H5 #84 | H9 (needs B's H6–H8) → 13 with the owner |
| B | 10a #77, 10b #80, 10c #82, 10d #85, 10e #87, 11a #88, 11b #89; 12 started as #90 | H6 → H7 → H8 |

Then: B merged H6 (#92, with a fix by A); the owner moved H7 (#93) and H8 to A. A did H8 in three PRs; A has H9 and 13 left.

#90 (step 12) was first held back for the problems below; A fixed them in the same PR (what it
does now: [M3-EXPRESSIVE.md](M3-EXPRESSIVE.md), step 12). The add menu case turned out not to
happen, because the add button is hidden while the pane is open, but the duplicate handler was
still removed.

- No tests for the new behaviour (TDD rule): the loading indicator while `STARTING`, the
  selected row, back closing the detail pane, reduced motion.
- The `PredictiveBackHandler` in `ListWithDetail` only collects the progress and then calls
  `panes.back()`. It adds no back animation and repeats the `BackHandler` that `MainActivity`
  already has. Because it is registered when the pane opens, it also wins over handlers
  registered earlier, such as the add menu's `BackHandler(expanded)`. With a detail pane open and
  the add menu expanded, back closes the pane instead of the menu.
- `rememberAnimationsEnabled()` reads `ANIMATOR_DURATION_SCALE` once per composition and is
  repeated in four places, with the same `waveSpeed` expression each time. It could live in
  `SyncProgress`.

## UI architecture

```
theme/            ApplicationTheme (MaterialExpressiveTheme + MotionScheme.expressive),
                  Color.kt (fallback light/dark schemes, used below Android 12),
                  Type.kt (AppFonts.text / AppFonts.wide = Roboto Flex at wdth 100 / 118)
ui/               shared expressive pieces, no screen logic:
                  StateLook.kt  StateTone.look() -> shape (cookie/flower/sunny/circle), container
                                color role, icon; StateBadge; StateTone.stateTextColor
                  Grouped.kt    groupedItemShape(index, count) (24 dp outside, 6 dp inside),
                                SectionHeader, SyncProgress (wavy, still wave)
views/            pure row states: FolderRowState, DeviceRowState (+ StateTone)
activities/
  MainActivity    state holder: MainUiState, refresh loop, menuOpen, MainActions/MenuActions
                  implementations, usage reporting, ACTION_EXIT
  MainContent.kt  MainUiState, FolderItem/DeviceItem, MainActions, MenuActions, MainTab,
                  navigationType() (bar < 600 dp, rail, expanded rail >= 1200 dp),
                  MainScaffold (top bar, AddMenu FAB), FolderList, DeviceList, ListTitle
  MainRows.kt     FolderRow, DeviceRow (grouped, StateBadge, override/revert dialogs)
  MainMenu.kt     overflow menu (device ID, recent changes, web GUI, import/export, restart, exit)
  MainStatus.kt   StatusTab, StatusHero, RunModeButtons (ToggleButton group), StatusFigures
  SystemFigures.kt StatusInfo + statusInfo() (+ statusLines() as its text form), figures
  Folder*/Device* FolderActivity + FolderForm + FolderEditScreen (+ FolderFiles), DeviceActivity
                  + DeviceForm + DeviceEditScreen; EditComponents.kt shared rows
  others          one Activity + one *Screen composable per secondary screen
settings/         SettingsActivity + Navigation 3 (SettingsNavigation.kt, SettingsRoute),
                  one *Screen per category, zhanghai preference library
onboarding/       OnboardingActivity + OnboardingScaffold + pages/
```

Rules the code follows:

- Screens are stateless composables (`state`, `actions` interface, `modifier`); the Activity owns
  state in `mutableStateOf` and implements the actions. Keep that split: it is what the tests
  drive and what step 8 reuses in panes.
- State is never shown by color alone: `StateBadge` gives every `StateTone` its own shape and
  icon (`StateLookTest` checks it). Text colors come from Material roles, never `R.color.text_*`.
- Fork-only strings are English only (`res/values/strings.xml`); replaced upstream keys stay with
  `tools:ignore="UnusedResources"` and the reason (decision 4 in the plan).
- Expressive APIs are opted in module-wide (`app/build.gradle.kts`); no `@OptIn` for them.

## Tests and how the UI is driven

- **Drivers** wrap a screen so behaviour tests survive redesigns; only the driver changes when
  the UI under it changes: `MainScreen.kt` (main), `FolderScreen.kt` (folder), the device driver
  inside `DeviceActivityTest.kt`. They find nodes by test tag, text or content description.
  Tags in use on the main screen: `folder:<label>`, `folderState:<label>`, `device:<name>`,
  `deviceStatus:<name>`, `light:<STATE>`, `totalProgress`, `mainMenu`, `force:<i>`,
  `statusHeadline`, `statusReason`, `statusFigure`.
- **Screenshots** (Roborazzi, Robolectric native graphics), see [TESTING.md](../TESTING.md):
  `RunningScreenRecorder` (`screenshots/RunningScreens.kt`) opens a screen against
  `FakeSyncthing`; `RunningScreenshotTest` (phone, light/dark), `WindowSizeScreenshotTest`
  (`screenshots/windows/`, compact 411×891, medium 700×960, expanded 840×900, large 1280×800,
  mdpi, EN + IT on compact/large), `ComponentGalleryScreenshotTest` (`screenshots/components/`).
  Re-record with `:app:recordRoborazziDebug`, then **look at every changed image** (side by side
  with `git show HEAD:<path>`) before committing.
- Instrumented tests (`app/src/androidTest`, web views) run in CI on an emulator. Locally only
  with `ANDROID_SERIAL=emulator-5554`; never on the owner's phone (it uninstalls the app).

## How to work

1. Branch from `main`: `feat/m3e-<topic>`, `refactor/<topic>`, `test/<topic>`.
2. TDD: write or change the test, run it, see it fail for the right reason, then the code.
3. `IS_COPILOT=true ./gradlew :app:ktfmtFormatMain :app:ktfmtFormatTest`, then the gate
   `IS_COPILOT=true ./gradlew :app:check :app:verifyRoborazziDebug`. Lint must stay at the four
   warnings already on `main` (UseKtx ×2, RequiresFeature, TrimLambda); detekt at zero.
4. Update the plan's step ("Done: …"), `AGENTS.md` ("Resume here") and TESTING.md when tests or
   screenshot groups change, in the same PR.
5. Commit: Conventional Commits, lower case, scope; end with the `Co-Authored-By` and
   `Claude-Session` lines. PR: `gh pr create --repo tiwiz/syncthing-android --base main`, body
   ending with the Claude Code line. Squash-merge when CI is green.
6. Stacked branches after a squash merge: `git rebase --onto origin/main <old-base> <branch>`
   run from anywhere (never `--onto origin/main <current-branch>` on the branch itself: that
   drops its commits; `git reflog` gets them back).

## Pitfalls already met

- material3 1.5: `ExposedDropdownMenu` is an extension (import it); `MaterialShapes.*` getters
  are `@Composable`; Compose Rules want keyed `SideEffect(key)` (new in 1.12) for effects that
  never suspend.
- Anything animating forever (moving wavy indicators, `rememberInfiniteTransition`) never lets
  Compose go idle: tests and screenshots hang. `SyncProgress` uses `waveSpeed = 0.dp`.
- A member property beats an extension with the same name (`StateTone.textColor` vs the
  composable one): pick distinct names.
- `DropdownMenu`/popups are found by compose tests across roots; use `hasAnyAncestor(hasTestTag)`.
- `ToggleButton` exposes `ToggleableState`; add `semantics { role = RadioButton; selected = … }`
  for single choice so drivers can read `Selected`.
- ktfmt does not rewrap KDoc lines: keep them under 100 columns by hand.
- detekt limits hit: `TooManyFunctions` (15 per file: split files), `MatchingDeclarationName`
  (a file with one class is named after it), `MagicNumber`, no `it` as an explicit lambda name.
- `currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true)` is needed to see the large
  class; `NavigationSuiteScaffoldDefaults.navigationSuiteType` alone never gives the expanded rail.
- CI: sometimes no checks start for a new PR (`gh pr checks` says "no checks"): close and reopen
  it. Emulator image downloads can be corrupt ("Error on ZipFile"), and Robolectric Maven fetches
  can time out: `gh run rerun <id> --failed`.
- Robolectric runs SDK 36: dynamic color is on in screenshots; the fallback scheme is only seen
  with `@Config(sdk = [30])`.

## Remaining plan

Sizes: S < 300 changed lines without goldens, M < 800, L above. Owner: **A** = lead agent,
**B** = helper agent. B's work touches no file A's work touches (see "Ownership").

### Step 8 — list-detail on large windows (A, 3 PRs, sequential)

Goal: from 840 dp (expanded) a tap on a folder or device opens it in a detail pane next to the
list; on an unfolded foldable the panes split on the hinge. Compact and medium keep launching
`FolderActivity` / `DeviceActivity`.

- **8a `refactor(folder): folder screen state outside the activity` (M).** Move form loading,
  validation, save/remove, dialogs and the actions out of `FolderActivity` into a
  `FolderEditController` (plain class, Compose state inside) with a small `FolderEditHost`
  interface for what only an Activity can do: launch the tree picker, folder type, pull order,
  versioning and sync conditions screens and get their results back, show toasts, send the
  local broadcast, close. `FolderActivity` becomes a thin host. Test: `FolderActivityTest`,
  `FolderDialogsTest`, `EditScreensTest` and all goldens unchanged (pure refactor).
  Done: `FolderEditController` + `FolderEditHost` + `FolderRequest` + `FolderEditArgs`
  (`FolderEditController.kt`); `FolderActivity` is the host, one result launcher per request.
- **8b `refactor(device): device screen state outside the activity` (M).** Same for
  `DeviceActivity` → `DeviceEditController` + `DeviceEditHost` (QR scanner result, share, close).
  Tests unchanged.
  Done: `DeviceEditController.kt` (`DeviceEditController`, `DeviceEditHost`, `DeviceRequest`,
  `DeviceEditArgs`); the host also shows the device ID dialog and passes the REST API once bound.
- **8c `feat(main): list and detail side by side on large windows` (L).** Add
  `androidx.compose.material3.adaptive:adaptive-layout` and `adaptive-navigation` (1.3.0, stable)
  to the catalog. In `MainContent`, wrap the folders and devices destinations in
  `ListDetailPaneScaffold` with `rememberListDetailPaneScaffoldNavigator<Selection>()`
  (`Selection` = folder id or device id, `@Parcelize`). The directive comes from
  `calculatePaneScaffoldDirective(currentWindowAdaptiveInfo(...))`; check that its hinge policy
  keeps panes off the hinge (fold posture via `androidx.window`). Detail pane hosts
  `FolderEditScreen` / `DeviceEditScreen` through the controllers; `MainActivity` implements the
  hosts and registers the activity-result launchers. Rules: selecting another row while the form
  has unsaved changes asks to discard (existing discard dialog); back closes the detail pane
  first (navigator back), then the existing back behaviour; a `VerticalDragHandle` resizes the
  panes. Tests first, in `MainActivityTest` under `@Config(qualifiers = "w840dp-h900dp")`: a tap
  opens the pane and starts no activity; the pane shows the folder's label; back closes it;
  unsaved changes ask before switching; compact still starts `FolderActivity`. Extend the
  drivers (`MainScreen.openInPane`, `detailShown`). Goldens: the window matrix (expanded, large)
  changes; add `windows/*_detail_*` captures with a selection.
  Done: `MainDetail.kt` (`ListWithDetail`, `paneDirective`, the scaffold value computed from the
  selection, `VerticalDragHandle`), `DetailPanes.kt` (`Detail`, selection, both hosts, result
  launchers, saved state), `MainActivityListDetailTest` (4), `windows/main_*_detail_*` (4).
  The pane opens only with two partitions (`calculatePaneScaffoldDirective`, hinge avoided by
  default); the list is `movableContentOf` so it keeps its scroll.

### Step 9 — folder and device screens (A, 1–2 PRs, after 8)

Restyle `FolderEditScreen` and `DeviceEditScreen` to the canvas: hero with `StateBadge` and the
label, `SyncProgress` while syncing, grouped sections (`groupedItemShape`, `SectionHeader`),
switches in grouped rows, bottom row with Pause (tonal) and Remove (outlined, error color). They
render the same in the activity and in the pane. Drivers (`FolderScreen`, device driver) adapt;
behaviour tests unchanged. Goldens: `activities/folder_*`, `device_*`, `running/*_edit`,
`windows/*_edit_*`.

### Step 10 — secondary screens (B, 5 PRs, independent of 8–9, any order)

One PR each, same recipe: grouped lists, `SectionHeader`, expressive top bars (the classic
`TopAppBar`/`MediumTopAppBar`/`LargeTopAppBar`: the flexible ones are not in material3
1.5.0-beta01, only their tokens), shapes from `ui/`; existing behaviour tests unchanged; goldens
of that screen re-recorded and reviewed.

- **10a** `SingleChoiceDialogActivity` (folder type, pull order) and `VersioningScreen`
  (versioning dialog). Goldens: `versioning/`, `other/folder_type*`, `other/pull_order*`.
- **10b** `SyncConditions*` and `FolderPicker*`. Goldens: `other/sync_conditions*`; the folder
  picker has none yet: add `other/folder_picker*` first, recorded before the restyle.
- **10c** `Share*`, `PhotoShootActivity`, `QRScannerActivity`. Goldens: `other/share*`,
  `other/photo_shoot*`, `other/qr_scanner*`.
- **10d** `LogActivity`, `recentchanges/`, `WebGuiActivity` / `WebViewActivity` top bars,
  `DeviceIdDialogFragment`. Goldens: `other/log*`, `other/recent_changes*`,
  `other/device_id_dialog*`, `webgui/`.
- **10e** `onboarding/` (scaffold and pages). Goldens: `onboarding/`. `OnboardingFlowTest`
  unchanged. Keep the TV focus behaviour.

New shared components for step 10 go first in their own small PR to `ui/` (with a gallery
screenshot), so A and B never edit `ui/` in parallel.

### Step 11 — settings (B, 1–2 PRs, after 10)

- **11a** Style: theme the zhanghai preference rows (or wrap them) to grouped items, section
  headers, `LargeFlexibleTopAppBar`. Goldens `settings/`, `running/settings_*`.
- **11b** On expanded windows, categories and detail side by side with
  `androidx.compose.material3.adaptive:adaptive-navigation3` (1.3.0) list-detail scene strategy
  in `SettingsNavigation.kt`. Tests: `SettingsNavigationTest` gains an expanded case.

### Step 12 — motion and polish (A, 1 PR, after 9)

Moving wave in the app with a test switch so tests keep `waveSpeed = 0` (e.g. a
`CompositionLocal`-free parameter read from a theme-level setting, or
`mainClock.autoAdvance = false` in tests); `LoadingIndicator` for `STARTING`; selected
button and row shape morphs; predictive back on the detail pane; respect "remove animations"
(`Settings.Global.ANIMATOR_DURATION_SCALE`).

### Step 13 — milestone (A + owner)

Native debug build, install on the Pixel 8 (`35121FDJH000AQ`, data kept: `adb install -r`),
plus AVDs for a foldable (Pixel Fold, API 35) and a tablet (Pixel Tablet, API 35): create them
with `avdmanager create avd -n fold -k "system-images;android-35;google_apis;x86_64" -d
pixel_fold` and `-d pixel_tablet`. Walk every screen in light/dark, English/Italian, portrait/
landscape, folded/unfolded; record results in AGENTS.md; create a GitHub milestone with the PRs.
Phone screenshots show the real device ID and API key: keep them in the scratchpad.

## Hilt and ViewModels

A second plan runs alongside this one: [HILT-VIEWMODELS.md](HILT-VIEWMODELS.md) (steps H1–H9,
same ownership). Its "Order with the Material 3 Expressive plan" section says when each H step
comes; screens move to ViewModels after their restyle, never in the same PR.

## Ownership while two agents work

| Area | Owner |
|---|---|
| `activities/Main*`, `MainActivity`, `SystemFigures.kt`, `Folder*`, `Device*`, `EditComponents.kt`, `views/` | A |
| `activities/SingleChoice*`, `Versioning*`, `SyncConditions*`, `FolderPicker*`, `Share*`, `PhotoShoot*`, `QRScanner*`, `Log*`, `WebView*`, `recentchanges/`, `webgui/`, `fragments/`, `onboarding/`, `settings/` | B |
| `ui/`, `theme/`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `res/values/strings.xml` | shared: small separate PRs, announce before editing |
| `docs/plans/M3-EXPRESSIVE.md`, `AGENTS.md` | each PR edits only its own step's line |

Rebase on `main` before opening a PR; goldens never conflict as long as each agent records only
the screenshot groups of its own screens (the window matrix belongs to A).
