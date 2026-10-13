# Things to fix

Known debt in app code, to address during the refactoring. One item per PR where possible. When an
item is done, delete it here in the same PR.

## Bugs pinned by characterization tests

1. `RestApi` sends the folder-sync-complete broadcast with action `.ACTION_NOTIFY_FOLDER_SYNC_COMPLETE`
   and permission `.permission.RECEIVE_SYNC_STATUS`: upstream 9a3851cc dropped the application id
   prefix, so apps registered for the documented
   `com.github.catfriend1.syncthingfork.ACTION_NOTIFY_FOLDER_SYNC_COMPLETE` receive nothing.

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
