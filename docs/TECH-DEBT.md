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
  `service/SyncthingService.kt`, and `service/Constants.kt` (one function per file location). Their
  helpers live in other files; splitting the APIs means changing every caller.

## TODOs

None.
