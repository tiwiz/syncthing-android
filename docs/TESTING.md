# Testing

## TDD flow

Adapted from [rock3r/headroom](https://github.com/rock3r/headroom/blob/main/docs/TESTING.md).

1. Write the test first.
2. Run just that test and check it fails for the reason you expect.
3. Write the minimum production code to make it pass.
4. Run the test again, then the gate (`IS_COPILOT=true ./gradlew :app:check`).
5. Refactor with the tests green.

## Current state

The project has **no tests** today: no `src/test`, no `src/androidTest`, and no test dependencies
in `gradle/libs.versions.toml`. Upstream's CONTRIBUTING asks not to add dependencies that are only
needed for tests, so test infrastructure stays in this fork.

The first task that needs a test adds the infrastructure, with the owner's approval:

- `junit:junit` (JUnit 4) as `testImplementation`, declared in the version catalog.
- `org.robolectric:robolectric` and `androidx.test:core` only when a test needs Android types.
- `androidx.compose.ui:ui-test-junit4` for Compose UI tests under Robolectric.

## Test layers

| Layer | Where | Runs on | Command |
|---|---|---|---|
| Unit tests | `app/src/test` | JVM, JUnit 4 | `IS_COPILOT=true ./gradlew :app:testDebugUnitTest` |
| One test class | `app/src/test` | JVM | `IS_COPILOT=true ./gradlew :app:testDebugUnitTest --tests '*LuhnTest'` |
| Android unit / Compose UI tests | `app/src/test` | Robolectric | same task |
| Instrumented tests | `app/src/androidTest` | Emulator with a native build | `./gradlew :app:connectedDebugAndroidTest` |

## What to test first

The code that is pure, or close to it, and carries the most risk:

- `util/Luhn`, `util/CertificateValidator.kt`, device id validation.
- `ConfigXml` read/write and `generateConfig` defaults, against fixture `config.xml` files in
  `app/src/test/resources`.
- The run-condition decision in `RunConditionMonitor.decideShouldRun()`. Extract the decision into
  a pure function of the inputs (preferences, network, battery, time) before testing it.
- Gson parsing of REST payloads in `model/`, against recorded JSON fixtures.
- `SyncthingRunnable` exit-code mapping and environment building.

## Fixtures, not live services

Tests never start the native binary or talk to a real Syncthing instance. REST behaviour is tested
against recorded JSON fixtures, and config handling against fixture `config.xml` files with fake
device ids and keys.
