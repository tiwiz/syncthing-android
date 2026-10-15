# Kotlin and Compose migration

Decided by the owner on 2026-10-13: every Java file becomes Kotlin and every View screen becomes
Compose, always with tests. When everything is done, a milestone is cut and the app is tried on the
phone. This replaces the earlier rule "do not convert Java files unless asked".

## Order

One topic per PR, each with the gate and CI green before merging.

1. **Java → Kotlin, bottom up.** Same behaviour, the existing tests are the safety net; code
   without tests gets characterization tests first, written against the Java code.
   1. `model/` (Gson POJOs, completion caches). Done (#31).
   2. `http/` (Volley requests, trust manager). Done (#32).
   3. Small helpers: `util/` (`Luhn`, `Compression`, `JobUtils`, `PermissionUtil`,
      `TextWatcherAdapter`), Dagger and the app class, receivers, small services. Done (#33).
   4. `Util`, split by topic into top-level functions with `@file:JvmName` facades: `Util` (shell
      and processes), `TextFormat`, `UiUtil`, `OsTrustStore`. Done (#34).
   5. `FileUtils`, split into `FileUtils` (storage paths, MIME table), `SafUtils` (SAF URIs,
      DocumentFile) and `FileOpener` (open in other apps). Done (#35).
   6. `ConfigXml` (with the model conversions and DOM helpers in their own files) and
      `ConfigRouter`. Done (#36).
   7. `Constants` and the force/schedule tiles. Done (#37).
   8. `NotificationHandler`. Done (#38).
   9. `SyncthingRunnable`. Done (#39).
   10. `EventProcessor`. Done (#40).
   11. `RunConditionMonitor`, split into the monitor, `RunConditionChecks` and `DeviceState`.
       Done (#41).
   12. `RestApi` (helpers in `RestApiSupport.kt`). Done (#42).
   13. `SyncthingService` (backup in `ConfigBackup.kt`, certificate files in `HttpsCertFiles.kt`,
       per-object run conditions in `ObjectRunConditions.kt`). Done (#43).
   Activities, fragments, adapters and custom Views are not converted to Kotlin first: the
   Compose rewrite replaces them.
2. **Screens → Compose**, smallest first: folder type, pull order and versioning dialogs, sync
   conditions, QR scanner, photo shoot, log, folder picker, share, web page, device, folder, then
   the main screen with its drawer, lists and status tab. Screenshot goldens are re-recorded in
   these PRs and every change is reviewed.
   - Folder type and pull order: one Compose radio list, `SingleChoiceDialogActivity`, in the
     same dialog window. Done (#44).
   - Versioning: `VersioningScreen` (type radios, number fields, versions path through the
     folder picker) replaces the dialog, its five fragments and `NumberPickerFragment`. Done (#45).
   - Sync conditions: `SyncConditionsScreen` on `SettingsScaffold` and the settings' preference
     components; `GlobalRunConditions`/`ObjectRunConditions` read and save the prefs. Done (#46).
   - QR scanner (`DecoratedBarcodeView` in `AndroidView`) and Syncthing Camera (intro on
     `OnboardingScaffold`). Done (#47).
   - Log: `LogActivity` with `LogText.kt` (filter, files). Done (#48).
   - Folder picker: `FolderPickerState` + `FolderPickerScreen`. Done (#49).
   - Share: `ShareScreen` + `ShareFiles.kt` (names, copy on the IO dispatcher). Done (#50).
   - Web page: `WebViewScreen` (WebView in `AndroidView`, Compose certificate notice). Done (#51).
   - Device: `DeviceForm` + `DeviceEditScreen`. Done (#52).
   - Folder: `FolderForm` + `FolderEditScreen` + `FolderFiles.kt`; shared parts in
     `EditComponents.kt`. Done (#53).
   - Main screen: row states and Kotlin adapters (#55), characterization tests through the
     `MainScreen` driver (#56), the Compose screen (`refactor/compose-main-screen`). Every screen
     is Compose now.
3. **Wrap-up.** `enableEdgeToEdge()` moves to `ThemedAppCompatActivity` (see TECH-DEBT.md), unused
   layouts and Views go, then the milestone and a run on the phone.

## Conversion rules

- Gson models keep their field names, order and defaults, as `@JvmField var` so Java callers keep
  field access. A field Java initialised is non-null; one it left `null`, and boxed
  `Boolean`/`Integer` ones, are nullable. Collections use read-only Kotlin types (Java still sees a
  mutable `java.util.List`).
- Java's `String.split` drops trailing empty strings and Kotlin's does not: use
  `splitLikeJava` (`util/JavaStrings.kt`) when converting a `split`.
- detekt applies in full to converted code. Where Java would throw on a null, `!!` becomes
  `checkNotNull(...)` (same place, `IllegalStateException` instead of `NullPointerException`;
  nothing in the app catches either), or `orEmpty()` where null was already ruled out.
- Static helpers that Robolectric shadows stay top-level functions in a `@file:JvmName` facade,
  never an `object`: calls into an object go through its instance, so a static shadow would be
  skipped (and `Util` would run `ps`/`kill` on the test host). Shadows name the facade with
  `@Implements(className = ...)`.
- Kotlin `Map<String, Any?>` reaches Java as `Map<String, ? extends Object>`; add
  `@JvmSuppressWildcards` while Java callers remain.
- Big classes that hit `TooManyFunctions`/`LargeClass` are split where that is natural; otherwise
  one suppression with a reason, listed in TECH-DEBT.md.
