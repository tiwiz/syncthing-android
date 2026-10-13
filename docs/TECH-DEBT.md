# Things to fix

Known debt in app code, to address during the refactoring. One item per PR where possible. When an
item is done, delete it here in the same PR.

## Bugs pinned by characterization tests

1. `QuickSettingsTileSchedule.updateTile` returns early when the state does not change. A new tile
   is already `STATE_INACTIVE`, so it keeps the manifest label "Run for 5 minutes" instead of the
   configured sync duration until it goes through another state.

## detekt suppressions

None left to remove. One is kept on purpose:

- `settings/SettingsUserInterfaceScreen.kt` `syncWebGuiTheme` (`TooGenericExceptionCaught`): the
  theme sync is fire-and-forget after the app theme already changed, and what
  `ConfigRouter.getGui` / `updateGui` can throw is any `RuntimeException`, which detekt flags just
  the same. The reason is in its KDoc.

## TODOs

| Where | What |
|---|---|
| `onboarding/OnboardingActivity.kt` `onCreate` | Move `enableEdgeToEdge()` to `ThemedAppCompatActivity` once every activity uses Compose (was a `TODO:`, rephrased for detekt) |
