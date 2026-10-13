# Things to fix

Known debt in app code, to address during the refactoring. One item per PR where possible. When an
item is done, delete it here in the same PR.

## Bugs pinned by characterization tests

Each one is pinned by a test commented `// CHARACTERIZATION:`. Fixing it means changing that test on
purpose.

1. `Util.readableFileSize` / `readableTransferRate` crash with `ArrayIndexOutOfBoundsException`
   from 1 PiB (1 PiB/s) up: the unit arrays stop at TiB.
2. `ConfigXml.addFolder` throws `NullPointerException` when `versioning` is null.
3. `ConfigXml.removeDevice` leaves the device inside the `<folder>` elements it shared.
4. `ConfigRouter.ignoreDevice` / `ignoreFolder` do nothing (they only log) while Syncthing is not
   running.
5. `Compression.fromValue` maps an unknown value to `NONE`, not the default `METADATA`.
6. `RestApi.readConfigFromRestApi` never retries: if version, config or status fails, the API never
   becomes available and the service stays in `STARTING`.
7. `EventProcessor` `FolderErrors` keeps the JSON quotes around the path in the "SD card full"
   notification.
8. `EventProcessor.onEvent`: the ignored event types fall through into the `RemoteIndexUpdated`
   case (its `break` is commented out).

## detekt suppressions

Each one has its reason next to it. Remove the suppression when the code no longer needs it.

| Where | Rule | What would remove it |
|---|---|---|
| `settings/SettingsActivity.kt` `LocalSyncthingService` | `CompositionLocalAllowlist` | Pass the service (or a state holder for it) to the screens explicitly, or allow it in `config/detekt/detekt.yml` (needs the owner's approval) |
| `settings/SettingsActivity.kt` `LocalServiceUpdateTick` | `CompositionLocalAllowlist` | Same as above: expose service state as an observable instead of a tick |
| `settings/SettingsNavigation.kt` `LocalSettingsNavigator` | `CompositionLocalAllowlist` | Pass navigation callbacks to the screens |
| `util/CompositionLocals.kt` `LocalActivityScope` | `CompositionLocalAllowlist` | Move the long-running work (config writes, exports) out of composables, e.g. into a ViewModel or the service |
| `webgui/WebGuiActivity.kt` `setWebViewProxy` | `TooGenericExceptionCaught` | Replace the reflective proxy hack (e.g. `androidx.webkit.ProxyController`), or catch the exact exceptions once they are known |
| `settings/SettingsUserInterfaceScreen.kt` `syncWebGuiTheme` | `TooGenericExceptionCaught` | Catch the specific exceptions `ConfigRouter.getGui` / `updateGui` can throw |

## TODOs

| Where | What |
|---|---|
| `onboarding/OnboardingActivity.kt` `onCreate` | Move `enableEdgeToEdge()` to `ThemedAppCompatActivity` once every activity uses Compose (was a `TODO:`, rephrased for detekt) |
| `onboarding/OnboardingScaffold.kt` `isCompactOnboardingScreen` | Use a window size class once `material3.adaptive` is a dependency (was a `TODO:`, rephrased for detekt) |
| `service/SyncthingService.java` `getBackupZipFile` | Find out why `PREF_BACKUP_REL_PATH_TO_ZIP` can be an empty string and fix the cause (Java, so detekt does not see it) |
