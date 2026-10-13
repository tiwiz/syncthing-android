# Things to fix

Known debt in app code, to address during the refactoring. One item per PR where possible. When an
item is done, delete it here in the same PR.

## Bugs pinned by characterization tests

None left. When a test commented `// CHARACTERIZATION:` pins a new bug, list it here.

## detekt suppressions

None left to remove. One is kept on purpose:

- `settings/SettingsUserInterfaceScreen.kt` `syncWebGuiTheme` (`TooGenericExceptionCaught`): the
  theme sync is fire-and-forget after the app theme already changed, and what
  `ConfigRouter.getGui` / `updateGui` can throw is any `RuntimeException`, which detekt flags just
  the same. The reason is in its KDoc.

## TODOs

| Where | What |
|---|---|
| `onboarding/OnboardingActivity.kt` `onCreate` | Move `enableEdgeToEdge()` to `ThemedAppCompatActivity` once every activity uses Compose (was a `TODO:`, rephrased for detekt). Not before: tried on 2026-10-13, on Android 14 it put dark icons on the View screens' blue status bar (main screen); `EdgeToEdgeTest` pins the difference |
