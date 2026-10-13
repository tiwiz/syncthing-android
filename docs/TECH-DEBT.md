# Things to fix

Known debt in app code, to address during the refactoring. One item per PR where possible. When an
item is done, delete it here in the same PR.

## Bugs pinned by characterization tests

None left. When a test commented `// CHARACTERIZATION:` pins a new bug, list it here.

## detekt suppressions

Each one has its reason next to it. Remove the suppression when the code no longer needs it.

| Where | Rule | What would remove it |
|---|---|---|
| `util/CompositionLocals.kt` `LocalActivityScope` | `CompositionLocalAllowlist` | Move the long-running work (config writes, exports) out of composables, e.g. into a ViewModel or the service |
| `settings/SettingsUserInterfaceScreen.kt` `syncWebGuiTheme` | `TooGenericExceptionCaught` | Catch the specific exceptions `ConfigRouter.getGui` / `updateGui` can throw |

## TODOs

| Where | What |
|---|---|
| `onboarding/OnboardingActivity.kt` `onCreate` | Move `enableEdgeToEdge()` to `ThemedAppCompatActivity` once every activity uses Compose (was a `TODO:`, rephrased for detekt) |
| `onboarding/OnboardingScaffold.kt` `isCompactOnboardingScreen` | Use a window size class once `material3.adaptive` is a dependency (was a `TODO:`, rephrased for detekt) |
