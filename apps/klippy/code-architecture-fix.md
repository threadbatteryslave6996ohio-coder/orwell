# Remaining architecture refactors

This file captures the next round of architecture cleanup after the current
`clients/client-core` extraction. The goal is to remove the remaining
high-friction duplication and tighten module boundaries without broad,
unrelated rewrites.

## 1. Finish extracting the desktop-client runtime shape — completed

Priority: high

`DesktopClientRunner` in `clients/client-core` now owns the scheduler lifecycle,
the shutdown hook, the startup banner, and the poll scheduling. Both desktop mains
build their platform-specific `DesktopClipboardMonitor` and hand it to the runner:
mac calls `start("Clippy client started.", config)`; linux passes the
`clipboardBackend` field via `start(label, config, extraFields)`. The banner
rendering is covered by `DesktopClientRunnerTest`.

The client bootstrap sequence is smaller than before, but `clients/mac` and
`clients/linux` still duplicate the same runtime structure:

- load env and `ClientConfig`
- validate poll interval
- create/connect file-locker client
- initialize auth
- create a `DesktopClipboardMonitor`
- create a scheduler and install the same shutdown hook
- print a startup line and schedule `monitor::poll`

Evidence:

- `clients/mac/src/main/java/dev/clippy/client/ClipboardClientApp.java:55-82`
- `clients/linux/src/main/java/dev/clippy/linux/LinuxClipboardClientApp.java:92-114`

Refactor target:

- Add a small `DesktopClientRunner` or `DesktopClientBootstrap` in
  `clients/client-core` that owns the scheduler lifecycle, startup logging, and
  polling loop.
- Keep only platform-specific clipboard-reader construction in `clients/mac`
  and `clients/linux`.

Expected result:

- the two desktop mains become thin adapters
- lifecycle policy lives in one place
- future fixes to startup/shutdown behavior happen once

## 2. Move Linux clipboard backend selection out of the app main — completed

Priority: high

Backend selection, environment probing, executable discovery, command/AWT clipboard
reading, and desktop-launch sanitization now live in the `dev.orwell.clients.linux.clipboard`
package (`LinuxClipboardReader`, `LinuxClipboardReaderFactory`, `CommandClipboardReader`,
`AwtClipboardReader`, `ProcessEnvironmentSanitizer`, package-private `Executables`).
`LinuxClipboardClientApp.main` now just calls `LinuxClipboardReaderFactory.create(env)`.
The sanitization test moved to `ProcessEnvironmentSanitizerTest`.

`LinuxClipboardClientApp` is still doing too much. It contains:

- backend selection rules
- environment probing
- executable discovery
- command-process clipboard reading
- AWT clipboard reading
- desktop-launch environment sanitization

Evidence:

- `clients/linux/src/main/java/dev/clippy/linux/LinuxClipboardClientApp.java:157-217`
- `clients/linux/src/main/java/dev/clippy/linux/LinuxClipboardClientApp.java:265-346`
- `clients/linux/src/main/java/dev/clippy/linux/LinuxClipboardClientApp.java:348-360`

Refactor target:

- Extract a dedicated Linux clipboard backend package, for example:
  - `LinuxClipboardReaderFactory`
  - `CommandClipboardReader`
  - `AwtClipboardReader`
  - `ProcessEnvironmentSanitizer`
- Keep `LinuxClipboardClientApp` focused on composing the client.

Expected result:

- the Linux main stops being the largest client class
- clipboard backend policy becomes testable without reflecting into the app
- later platform-specific fixes do not require touching bootstrap code

## 3. Extract auth audit logging from the Linux client — completed

Priority: high

`AuthAuditLogger` now owns the Linux auth-refresh audit concern: it serializes
records through the shared Jackson mapper (`ClipboardJson.mapper()`, replacing the
hand-rolled `jsonEscape`), exposes `record(...)` for startup logging, and supplies
the `ClipboardApiClient.AuthRefreshListener` via `refreshListener()` instead of the
app building it inline. `AuthAuditLoggerTest` covers the serialization.

The Linux app still owns a second concern unrelated to clipboard polling:
writing auth refresh audit records to the offline log.

Evidence:

- `clients/linux/src/main/java/dev/clippy/linux/LinuxClipboardClientApp.java:61-86`
- `clients/linux/src/main/java/dev/clippy/linux/LinuxClipboardClientApp.java:117-155`
- `clients/linux/src/main/java/dev/clippy/linux/LinuxClipboardClientApp.java:219-263`

Problems:

- auth auditing is mixed into app bootstrap
- JSON formatting is hand-rolled here instead of using the shared JSON utility
- Linux is the only client with this behavior, but the implementation is
  embedded in the main class rather than isolated as a Linux-only component

Refactor target:

- Extract an `AuthAuditLogger` or `AuthRefreshAuditSink`
- serialize through shared JSON infrastructure instead of `jsonEscape(...)`
- inject it into `ClipboardApiClient.AuthRefreshListener` rather than building
  the listener inline inside the app

Expected result:

- Linux-specific audit behavior stays Linux-specific, but the main class no
  longer owns transport, serialization, and persistence details

## 4. Replace the boolean-heavy `DesktopClipboardMonitor.Options` — completed

Priority: medium

The `Options` flag record is gone. A sealed `DesktopClipboardPolicy` interface with two
named implementations, `LinuxClipboardPolicy` and `MacClipboardPolicy`, now models the
platform decisions; the sealed hierarchy makes invalid flag combinations unconstructable.
`DesktopClipboardMonitor` takes `(offlineLogPath, policy)` directly, and each platform main
passes `new LinuxClipboardPolicy()` / `new MacClipboardPolicy()`. `DesktopClipboardPolicyTest`
locks the two decision matrices.

`DesktopClipboardMonitor` centralizes good logic, but its behavior is currently
driven by a record with several booleans:

- `ignoreEmptyContent`
- `flushPendingBeforeRead`
- `includeBackendInReadErrors`
- `logReadRecovery`

Evidence:

- `clients/client-core/src/main/java/dev/clippy/clients/core/DesktopClipboardMonitor.java:165-182`

Problems:

- behavior is encoded as a flag matrix
- Linux vs mac behavior is implicit rather than modeled directly
- adding a third desktop client mode will likely add more booleans

Refactor target:

- Replace the flag record with an explicit policy type, for example:
  - `DesktopClipboardPolicy`
  - `LinuxClipboardPolicy`
  - `MacClipboardPolicy`
- or split the few policy decisions into named strategy interfaces

Expected result:

- platform behavior becomes self-documenting
- it becomes harder to construct invalid flag combinations
- bug fixes around pending-offline handling can be localized by policy

## 5. Give offline-sync its own configuration/bootstrap layer — completed

Priority: medium

`OfflineSyncConfig` now parses the offline-log argument and environment (endpoint,
sync interval, dead-letter path, configured client id, auth fields) with named
parsing helpers; `OfflineSyncBootstrap` owns the wiring order (build sources, await
the first snapshot, resolve the client id, initialize auth, run the monitor).
`OfflineClipboardSyncApp.main` is now just `OfflineSyncBootstrap.fromArgs(args).run()`.
Parsing/derivation is covered by `OfflineSyncConfigTest`.

`OfflineClipboardSyncApp` is improved, but it still performs a large amount of
conditional startup wiring itself:

- offline log selection
- file-locker record source wiring
- dead-letter path creation
- sync interval parsing
- initial snapshot/client-id derivation
- auth session creation
- gateway/service/monitor assembly

Evidence:

- `clients/offline-sync/src/main/java/dev/clippy/sync/OfflineClipboardSyncApp.java:28-72`

Refactor target:

- Introduce a small `OfflineSyncConfig` plus `OfflineSyncBootstrap`
- move client-id derivation and sync-interval parsing behind named methods
- leave `main()` as argument parsing + bootstrap invocation

Expected result:

- startup invariants become explicit
- offline-sync behavior is easier to test as configuration, not only as an
  end-to-end loop
- the app entry point stops being the policy coordinator

## 6. Unify server launcher/application bootstrapping — completed

Priority: medium

`SpringServerBootstrap.start(appClass, loggingFileName, beforeRun, properties, sourceName)`
(in `server-bootstrap`, now depending on `utils`) owns the shared order: configure the
logging directory, run any server-specific pre-start hook, then boot Spring. All three
`start()` methods use it — `server` with no hook, `auth/server` passing
`logLocalDatabaseIfApplicable`, `../combined-server` passing `logCombinedModeDisclaimer` —
so logging/property wiring changes happen once. The `run(...)` overload is retained.
(The env-schema `from()`/`springProperties()` per module stays explicit — see item 8.)

The three server modules still share the same startup shape with minor
variations.

Evidence:

- launchers:
  - `server/src/main/java/dev/clippy/server/ClippyServerLauncher.java:15-29`
  - `auth/server/src/main/java/dev/clippy/auth/ClippyAuthServerLauncher.java:15-29`
  - `../combined-server/src/main/java/dev/clippy/combined/CombinedServerLauncher.java:13-68`
- applications:
  - `server/src/main/java/dev/clippy/server/ClippyServerApplication.java:13-26`
  - `auth/server/src/main/java/dev/clippy/auth/ClippyAuthServerApplication.java:12-27`
  - `../combined-server/src/main/java/dev/clippy/combined/CombinedServerApplication.java:24-37`

Refactor target:

- Extract a small shared launcher/bootstrap helper in `utils` for:
  - environment resolution
  - logging-directory configuration
  - Spring default-property wiring
- Keep the sibling combined-server app's special env-file behavior as an override, not a reason
  to leave all startup code duplicated

Expected result:

- startup behavior stays consistent across all server entry points
- logging/property-source changes happen once
- the server modules retain domain-specific env schemas without repeating the
  same application shell

## 7. Remove repeated JPA property assembly in combined-server — completed

Priority: medium

`CombinedJpaProperties.from(Environment, ddlAutoProperty)` now owns the Hibernate
property map (`hbm2ddl.auto` + `jdbc.time_zone`), and both `CombinedAuthDatabaseConfiguration`
and `CombinedClipboardDatabaseConfiguration` call it instead of assembling the map inline.
`CombinedJpaPropertiesTest` covers it.

The two combined-server DB configuration classes duplicate the same Hibernate
property construction.

Evidence:

- `../combined-server/src/main/java/dev/clippy/combined/CombinedAuthDatabaseConfiguration.java:58-63`
- `../combined-server/src/main/java/dev/clippy/combined/CombinedClipboardDatabaseConfiguration.java:62-67`

Refactor target:

- shared `CombinedJpaProperties.jpaProperties(Environment, ddlAutoProperty)`

Expected result:

- less drift risk between auth and clipboard persistence configuration
- easier to add future shared JPA settings once

## 8. Consolidate env-schema boilerplate

Priority: low

The env classes still repeat the same pattern:

- declare many static `EnvOption<?>` constants
- build them in a static block
- expose `from(Map<String,String>)`
- expose `springProperties(Env)`

Evidence:

- `server/src/main/java/dev/clippy/server/ServerEnvs.java`
- `auth/server/src/main/java/dev/clippy/auth/AuthServerEnvs.java`
- `../combined-server/src/main/java/dev/clippy/combined/CombinedEnvs.java`

Refactor target:

- do not chase “clever” generic metaprogramming here
- extract only the repetitive plumbing:
  - map builders for Spring properties
  - small helper for repeated datasource/JPA key mappings

Expected result:

- less manual property-map noise
- keep the env declarations explicit and readable

## 9. Clean up small utility duplication in `utils` — completed

Priority: low

`Strings.requireNonBlank(value, field)` now backs the trim/blank checks in
`EnvOption.validateName` and the `CustomLogger` constructor, and a package-private
`EnvOptions.putUnique(map, option)` holds the duplicate-option detection shared by
`EnvSchema` and `EnvClassBuilder`. Covered by `StringsTest`.

There are still repeated internal validation patterns:

- duplicate-option detection:
  - `utils/src/main/java/dev/clippy/env/EnvSchema.java:11-18`
  - `utils/src/main/java/dev/clippy/env/EnvClassBuilder.java:33-39`
- non-blank string normalization:
  - `utils/src/main/java/dev/clippy/env/EnvOption.java:53-60`
  - `utils/src/main/java/dev/clippy/env/EnvType.java:45-51`
  - `utils/src/main/java/dev/clippy/utils/logger/CustomLogger.java:24-28`

Refactor target:

- small internal helper utilities only
- no public abstraction layer unless there is a real second use outside `utils`

Expected result:

- fewer hand-written validation branches
- less risk of slightly different error semantics across helpers

## 10. Restore targeted app-level tests after the extraction — completed

Priority: medium

App-level and shared coverage was rebuilt alongside the extraction:
`DesktopClientRunnerTest` (banner), `DesktopClipboardPolicyTest` (platform decision
matrices), `ProcessEnvironmentSanitizerTest` + `AuthAuditLoggerTest` (Linux backend
sanitization + auth-audit serialization), `ClipboardClientAppTest` (mac clipboard read,
via an extracted `clipboardReader(Clipboard)` factory tested with a real headless
`Clipboard`), and `DummyClientAppTest` now covers auth-refresh failure handling. The
dummy client was also fixed: `sendCommand` catches the `RuntimeException` (missing/expired
token or failed refresh) that `ClipboardApiClient` surfaces, instead of terminating the
interactive client (bugs.md finding #3).

The current extraction moved useful behavior into shared units, but platform
tests were reduced hard:

- Linux test coverage is now mostly one environment-sanitization test
- mac app-specific tests were removed entirely

Evidence:

- `clients/linux/src/test/java/dev/clippy/linux/LinuxClipboardClientAppTest.java`
- deleted `clients/mac/src/test/java/dev/clippy/client/ClipboardClientAppTest.java`
- `clients/dummy/src/test/java/dev/clippy/dummy/DummyClientAppTest.java`

Refactor target:

- keep shared behavior tested in `client-core`
- add small app-level tests for platform-only wiring:
  - Linux backend selection
  - Linux auth-audit listener wiring
  - mac startup/wiring invariants
  - dummy-client auth refresh failure handling

Expected result:

- the architecture stays refactorable because each layer has its own tests
- app-level regressions are caught without forcing everything into end-to-end
  tests

## Suggested order

1. desktop runtime extraction
2. Linux clipboard backend extraction
3. Linux auth audit extraction
4. `DesktopClipboardMonitor` policy cleanup
5. offline-sync bootstrap/config extraction
6. server bootstrap unification
7. combined-server JPA helper
8. restore app-level tests
9. smaller env/utils cleanups
