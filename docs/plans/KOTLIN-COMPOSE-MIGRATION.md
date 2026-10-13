# Kotlin and Compose migration

Decided by the owner on 2026-10-13: every Java file becomes Kotlin and every View screen becomes
Compose, always with tests. When everything is done, a milestone is cut and the app is tried on the
phone. This replaces the earlier rule "do not convert Java files unless asked".

## Order

One topic per PR, each with the gate and CI green before merging.

1. **Java → Kotlin, bottom up.** Same behaviour, the existing tests are the safety net; code
   without tests gets characterization tests first, written against the Java code.
   1. `model/` (Gson POJOs, completion caches). Done in the first PR.
   2. `http/`, `util/` (small helpers first, then `FileUtils`, `ConfigXml`, `ConfigRouter`).
   3. `service/` and `receiver/` (`Constants`, tiles, `NotificationHandler`, `RunConditionMonitor`,
      `EventProcessor`, `SyncthingRunnable`, `RestApi`, `SyncthingService`), Dagger.
2. **Screens → Compose**, smallest first: folder type, pull order and versioning dialogs, sync
   conditions, QR scanner, photo shoot, log, folder picker, share, web page, device, folder, then
   the main screen with its drawer, lists and status tab. Screenshot goldens are re-recorded in
   these PRs and every change is reviewed.
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
- Kotlin `Map<String, Any?>` reaches Java as `Map<String, ? extends Object>`; add
  `@JvmSuppressWildcards` while Java callers remain.
- Big classes that hit `TooManyFunctions`/`LargeClass` are split where that is natural; otherwise
  one suppression with a reason, listed in TECH-DEBT.md.
