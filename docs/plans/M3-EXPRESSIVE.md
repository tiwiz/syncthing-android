# Material 3 Expressive and adaptive layouts

Proposed on 2026-10-16, after the Kotlin/Compose migration
([KOTLIN-COMPOSE-MIGRATION.md](KOTLIN-COMPOSE-MIGRATION.md)). The target is the design canvas the
owner approved: <https://claude.ai/artifact/BzMQAgmqaUHNL16zdTXfvf> (phone, foldable, tablet and a
system sheet with colors, type, shapes and window size classes).

Goal: every screen uses Material 3 Expressive (`MaterialExpressiveTheme`, expressive shapes,
button groups, wavy progress, FAB menu, grouped lists) and adapts to the window: navigation bar on
phones, rail on foldables and small tablets, expanded rail and list-detail panes on large screens,
panes split on the hinge of an unfolded foldable.

## Decisions for the owner (before PR 1)

1. **Experimental APIs.** Most expressive components are `@ExperimentalMaterial3ExpressiveApi`
   in material3 1.4.x. Proposal: bump `compose-material3` to the newest release that has them, and
   opt in once for the module (`-opt-in` compiler flag in `app/build.gradle.kts`) instead of
   annotating every file. API churn is accepted; each bump is its own PR.
2. **New dependencies.** `androidx.compose.material3.adaptive:adaptive`, `adaptive-layout`,
   `adaptive-navigation` (window size classes, `ListDetailPaneScaffold`, hinge-aware pane
   directive) and `material3-adaptive-navigation-suite` (`NavigationSuiteScaffold`).
3. **Font.** The app ships on F-Droid, so downloadable fonts through Google Play services are not
   an option. Either bundle Roboto Flex (variable, about 1.7 MB, width axis for display text as
   in the design) or keep the system font and get the expressive feel from weight and size only.
   Proposal: bundle Roboto Flex.
4. **Run mode labels.** The status tab's three choices become "Automatic", "Run", "Stop" with a
   helper line, as in the design. These are new string keys (existing keys are never renamed);
   the old three keys are deleted with their translations once unused, and Weblate translates the
   new ones (until then other languages show English).
5. **Main screen menu.** The drawer goes. Its entries (device ID, recent changes, web GUI,
   settings, restart, exit) move to the navigation rail on medium and larger windows and to a
   modal expanded rail opened from the menu button on phones.

## Rules for every PR

- One topic per PR, gate and CI green, squash merge.
- Behaviour tests do not change: they drive screens through `MainScreen`, `FolderScreen`,
  `DeviceScreen` and the other drivers; only a driver changes when the UI under it changes
  (tags, how a menu opens). A new behaviour (for example list-detail) gets new tests first.
- Screenshots are re-recorded in the PR that changes a screen and every diff is reviewed. From
  PR 2 on, the main, folder, device and status screenshots exist at four window sizes and in
  Italian, the longest common translation.
- State is never shown by color alone: a shape, an icon or a label goes with it
  (contrast at least 4.5:1, checked on the screenshots in light and dark).
- Dynamic color stays the default on Android 12+; the blue seed scheme is the fallback.

## Order

1. **Theme foundation.** `MaterialExpressiveTheme` in `ApplicationTheme`, fallback light and dark
   schemes from the seed (`theme/Color.kt`), emphasized typography (`theme/Type.kt`), shapes and
   the expressive motion scheme. Unit test for the scheme choice (dynamic vs fallback, light vs
   dark, `PREF_APP_THEME`). All screenshots re-recorded once, nothing else changes.
2. **Window size screenshot matrix.** Adaptive dependencies, a test helper that renders a screen
   at compact (411 dp), medium (700 dp), expanded (840 dp, foldable) and large (1280 dp, tablet
   landscape). Records today's layouts at those sizes as the baseline, plus Italian variants. No
   app change.
3. **Shared components** in `theme/` or a new `ui/` package:
   - `StateStyle`: one pure mapping from `FolderRowState`/`DeviceRowState` to shape (cookie,
     flower, sunny), container color, icon and label. Unit tested for every state.
   - `StateShapeIcon`, grouped list item shapes (24 dp outer, 6 dp inner corners),
     `SectionHeader`, wavy progress wrappers.
   - A component gallery screenshot in light and dark.
4. **Main screen navigation.** Tabs, pager and drawer become `NavigationSuiteScaffold`: bar on
   compact, rail on medium/expanded, expanded rail with labels on large. Drawer entries move as in
   decision 5. Driver: `openDrawer()` becomes `openMenu()`; every `MainActivityTest` stays.
5. **Folders list.** Large headline, total progress card with wavy indicator and rates, grouped
   rows with state shapes, FAB menu (add folder, add device, scan QR). The FAB menu's actions start
   the same intents as today's buttons (existing tests).
6. **Devices list.** Same pattern: state shapes per `DeviceRowState.Status`, connected rows show
   rates, disconnected rows the last seen time.
7. **Status tab.** Running hero with shape, connected `ButtonGroup` with the new labels, run
   conditions as a grouped list with met/not met icons, figures as a three-tile grid.
   `StatusLinesTest` and the force start/stop tests stay as they are.
8. **List-detail on large windows**, in three PRs:
   1. Extract the folder screen's state and actions from `FolderActivity` into a controller the
      activity hosts (pure refactor, `FolderActivityTest` unchanged).
   2. The same for `DeviceActivity`.
   3. `ListDetailPaneScaffold` in the main screen from 840 dp: selecting a row opens the detail
      pane instead of a new activity; panes split on the hinge (`FoldingFeature`), draggable
      divider; unsaved-changes dialog before switching selection; back closes the pane first.
      Compact windows keep launching the activities. New tests through the drivers at expanded
      size first.
9. **Folder and device screens.** Hero with the state shape, grouped sections, wavy progress for
   folders, toggles in grouped rows, bottom button row (pause, remove). Drivers unchanged.
10. **Secondary screens**, one or two per PR: single choice dialogs (folder type, pull order),
    versioning, sync conditions, folder picker, share, log, QR scanner and camera, web GUI and web
    page top bars, recent changes, device ID dialog, onboarding.
11. **Settings.** Expressive styling of the preference lists (`me.zhanghai` preference library:
    theme it, or replace its rows with our grouped items where it falls short) and, on large
    windows, categories and detail side by side with a Navigation 3 list-detail scene.
12. **Motion and polish.** Shape morph on selection and press, expressive `LoadingIndicator` while
    Syncthing is starting, predictive back on the detail pane, reduced motion respected.
13. **Milestone.** Native debug build on the Pixel 8 (portrait and landscape), plus emulator AVDs
    for a foldable (Pixel Fold, folded and unfolded) and a tablet (Pixel Tablet); walk every
    screen, light and dark, English and Italian; write the results in AGENTS.md.

## Risks

- Experimental APIs change between material3 releases: keep their use behind our own small
  wrappers (PR 3) so a rename touches one file.
- Robolectric and animations: wavy indicators and shape morphs animate; screenshots must freeze
  the compose clock (`mainClock.autoAdvance = false`) or they flake.
- Screenshot count grows (today 119 goldens); the size matrix covers only the main, folder,
  device and status screens.
- List-detail changes how folder and device screens are hosted; it is the riskiest step, which is
  why it comes after the visual work and has its own refactor PRs first.
