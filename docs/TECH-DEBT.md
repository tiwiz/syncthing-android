# Things to fix

Known debt in app code, to address during the refactoring. One item per PR where possible. When an
item is done, delete it here in the same PR.

## Bugs pinned by characterization tests

None left. When a test commented `// CHARACTERIZATION:` pins a new bug, list it here.

## detekt suppressions

None left to remove. Kept on purpose:

- `settings/SettingsViewModels.kt` `syncWebGuiTheme` (`TooGenericExceptionCaught`): the
  theme sync is fire-and-forget after the app theme already changed, and what
  `ConfigRouter.getGui` / `updateGui` can throw is any `RuntimeException`, which detekt flags just
  the same. The reason is in its KDoc.
- `SyncthingModule.kt` `getIoDispatcher` (`InjectDispatcher`): the one place `Dispatchers.IO` is
  named; everything else gets it injected through `@IoDispatcher`.
- `settings/SettingsSyncthingOptionsScreen.kt` (`MutableParams`): the preference library edits the
  options through the `MutableStateFlow` the screen passes it.

Kept from the Kotlin migration:

- `model/Connections.kt` `connections` (`MemberNameEqualsClassName`): the JSON field Syncthing
  sends has the class's name.
- `TooManyFunctions` on classes whose public API has one method per config operation:
  `util/ConfigXml.kt`, `util/ConfigRouter.kt`, `service/RestApi.kt` (also `LargeClass`),
  `service/SyncthingService.kt` (also `LargeClass` since #145), and `service/Constants.kt` (one function per file location). Their
  helpers live in other files; splitting the APIs means changing every caller.

## TODOs

- XR: `KEYCODE_BACK` does not close a spatial dialog in Full Space: tried on the file manager
  suggestion, 90 s later it was still open, with the dialog's window focused and no ANR. Its
  `dismissOnBackPress` is true and tapping the device ID dialog's close button works. Sent with
  `adb shell input`, not yet with the emulator's own back; the cause is unknown.
- XR: the web GUI in a Full Space panel gave three ANRs in a row on the 4-thread emulator host
  ("Waited for FocusEvent"; main thread 10 s running, 167 s waiting for CPU), and none in the same
  time in Home Space. The page does load and draw. Check on real hardware whether this is only the
  host.

- A unit test now and then fails with an NPE from `SyncthingConnection.onServiceDisconnected` on
  whichever test is running. Robolectric's `unbindService` calls it with a null `ComponentName`,
  which Android does not, and the parameter is non-null in Kotlin. Seen on
  `MainViewModelDetailTest` under load (full gate next to the XR emulator). A rerun passes. Fix it
  in the test setup or a shadow, not in app code.

- While Syncthing is stopped, the main screen's refresh (every second) reads `config.xml` twice on
  the main thread, once for the folders and once for the devices (`ConfigRouter` loads the file for
  every offline read). Reading it off the main thread, and only when the file changed, needs
  `MainViewModel.refresh()` to become asynchronous. The per-device reads are gone (#110).
- An import extracts the whole zip to `filesDir/import-staging` while the originals (index
  database included) still exist, so peak disk use is roughly their sum. Accepted for the atomic
  import (#148); `restoreBackup()` checks the free space first, so a backup that does not fit fails
  with an explicit log message instead of an ENOSPC halfway.
- `activities/FolderEditController.kt:279-282`: `servedFolder()` deep-copies the folder list with
  Gson on the main thread at save time. The cost is proportional to the `sendConfig` merge that
  follows, so it is fine for now.
- `SyncthingServiceCertificateTest`, "a certificate Syncthing replaces with a generated one is
  rolled back", failed once in CI (#164) with a `FileNotFoundException` at its
  `awaitBackground { certFile.readBytes()... }`, and passed on the rerun. Likely cause, from reading
  the code and not reproduced: `backupFile` moves the certificate to `.bak` before
  `writeBytesAtomic` puts the new one in place, so the test can read while the file is missing. The
  fix would be for the test's poll to check `certFile.exists()` first.
- `activities/MainContent.kt` and `activities/MainDetail.kt` still call the deprecated
  `currentWindowAdaptiveInfo()`; `ServiceChip` and the settings use `currentWindowAdaptiveInfoV2()`.
  V2 also reports the large and extra-large width classes. `MainContent` already asks for them
  (`supportLargeAndXLargeWidth = true`), but the pane directive in `MainDetail` does not, so the
  pane layout may change: this needs its own PR with a screenshot review.
