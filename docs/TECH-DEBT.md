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

Kept from the Kotlin migration:

- `model/Connections.kt` `connections` (`MemberNameEqualsClassName`): the JSON field Syncthing
  sends has the class's name.
- `TooManyFunctions` on classes whose public API has one method per config operation:
  `DaggerComponent` (one `inject` per class), `util/ConfigXml.kt`, `util/ConfigRouter.kt`, `service/RestApi.kt` (also `LargeClass`),
  `service/SyncthingService.kt`. Their
  helpers live in other files; splitting the APIs means changing every caller.

## TODOs

| Where | What |
|---|---|
| `activities/LogText.kt` | Removing the milliseconds from Android log lines also removes the space before the tag (`10:00:00I/Tag`), as the Java version did; pinned by `LogActivityTest`. |
