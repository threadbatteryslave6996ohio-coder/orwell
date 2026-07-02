# Refactoring Plan — Remove Code Duplication

This document catalogs code duplication found across the codebase and proposes
concrete refactorings to remove it. Findings are grouped by area and ordered by
impact. Several items build directly on the in-progress `clients/client-core`
extraction (branch `refactor/shared-client-core`).

**Suggested order:** 1 → 2 → 3 → 5 → 4 → 6, ideally as separate commits per group.

---

## 1. Client apps — finish the `client-core` extraction (highest leverage)

The four client mains each repeat the same bootstrap sequence. `client-core`
already owns the hard parts (`ClipboardApiClient`, `DesktopClipboardMonitor`,
`ClipboardEndpoint`, `ClientIdentity`), but the wiring around them is still
copy-pasted 3–4 times.

Affected mains:
- `clients/mac/src/main/java/dev/clippy/client/ClipboardClientApp.java`
- `clients/linux/src/main/java/dev/clippy/linux/LinuxClipboardClientApp.java`
- `clients/dummy/src/main/java/dev/clippy/dummy/DummyClientApp.java`
- `clients/offline-sync/src/main/java/dev/clippy/sync/OfflineClipboardSyncApp.java`

| Duplicated logic | Where | Proposed home |
|---|---|---|
| Env→config loading (endpoint, authServerUrl, clientId/secret/token), ~6–8 lines | all 4 mains (mac 51–60, linux 87–96, dummy 32–36, offline-sync 54–79) | new `ClientConfig` record + `ClientConfig.load(Env)` factory in client-core |
| `new ClientAuthSession(...)` construction | all 4 mains (mac 69, linux 98, dummy 38, offline-sync 80) | fold into `ClientConfig.load` |
| Auth init (`canRefresh`/`refresh`/`hasToken` + error messages) | mac 88–98, linux 105–120, dummy 39–47 | new `ClientAuthInitializer.initialize(authSession, cfg)` in client-core |
| File-locker socket resolution + client creation | mac 70–72, linux 99–101, offline-sync 55–57 | new `OfflineFileLockerFactory.create(Env)` |
| `shutdown(ScheduledExecutorService)` — byte-identical | mac 111–120, linux 259–268 | one helper in client-core |
| `validatePollIntervalMs` (differs only by min value) | mac 100–105 (min 1), linux 224–229 (min 100) | `PollIntervalValidator(min)` taking the floor as a parameter |
| Exception message formatting (`failureMessage` / `messageWithCause`) | dummy 104–107, linux 150–160, plus `DesktopClipboardMonitor` 165–176 | extract `ExceptionMessages` util; have the monitor use it too |
| `defaultClientId()` wrapper around `ClientIdentity.hostnameOrRandom(prefix)` | all 4 mains (differs only by prefix) | fold prefix into `ClientConfig.load`, or add named helpers on `ClientIdentity` |

**Already provided by client-core (do not re-implement):** `ClipboardEndpoint.from`,
`ClipboardApiClient`, `ClientIdentity.hostnameOrRandom`, `DesktopClipboardMonitor`,
`ClipboardJson.write`.

**Outcome:** a `ClientConfig` record + `ClientAuthInitializer` collapses most of
each `main()` down to a few lines.

---

## 2. Hand-rolled JSON — completed

Clipboard serialization, required-field extraction, timestamp parsing, and the
shared `ObjectMapper` now live in `clients/client-core/.../ClipboardJson.java`.
Offline sync uses that utility for snapshots, server responses, and dead-letter
records; its local `OfflineJson` copy has been removed.

---

## 3. Duplicated auth DTOs across module boundaries — completed

The `auth/api` module now owns `LoginRequest`, `LoginResponse`,
`CheckTokenRequest`, and `CheckTokenResponse`. Both `auth/server` and
`auth/client` use those records, and downstream modules that reference a DTO
directly declare an `auth-api` dependency.

---

## 4. Server bootstrap boilerplate (3 servers) — completed

`server`, `auth/server`, and `combined-server` repeat the same shapes.

- `resolveEnvironment()` — identical in both launchers:
  - `server/.../ClippyServerLauncher.java` 23–29
  - `auth/server/.../ClippyAuthServerLauncher.java` 23–29
- `*Envs` classes — same static-schema + `from()` + `springProperties()` skeleton:
  - `server/.../ServerEnvs.java` 22–53
  - `auth/server/.../AuthServerEnvs.java` 21–50
  - `combined-server/.../CombinedEnvs.java` 28–71
- Application `start()` — same parse-env → configure logging → build
  `SpringApplication` → add property source → run:
  - `server/.../ClippyServerApplication.java` 19–28
  - `auth/server/.../ClippyAuthServerApplication.java` 19–29
  - `combined-server/.../CombinedServerApplication.java` 29–41
- `jpaProperties()` duplicated verbatim between the two combined-server DB configs:
  - `combined-server/.../CombinedAuthDatabaseConfiguration.java` 62–67
  - `combined-server/.../CombinedClipboardDatabaseConfiguration.java` 66–71

The `server-bootstrap` module now owns the shared `SpringApplication` property
source and startup mechanics without adding Spring dependencies to `utils` or
desktop clients. Environment validation, logging, and server-specific pre-start
behavior remain explicit in each application. Standalone launchers delegate
working-directory discovery directly to `EnvFiles`, and combined database
configurations share `CombinedJpaProperties`.

---

## 5. POM / build duplication — completed

Dependency and plugin versions now live once per Maven inheritance tree. The
root parent manages the main reactor; `auth/pom.xml` mirrors the required
management because the auth repository remains independently buildable.

The stale JUnit override in `utils` was removed. Auth compiler configuration is
now inherited from `auth/pom.xml`, replacing the Java 17 client override and
duplicate server configuration.

Spring Boot, Testcontainers, Jackson, JUnit, compiler, Surefire, and packaging
plugin versions are managed by the parents. The five executable clients inherit
one shade execution parameterized by `${mainClass}`; file-locker keeps only its
attached-artifact override. Redundant jar manifest configuration was removed
because the shade transformer owns the final executable manifest.

---

## 6. utils internal cleanup (minor)

- A "require non-blank" pattern (null-check + trim + `isEmpty`) recurs in:
  - `utils/.../logger/CustomLogger.java` (21, 28–32, 36, 62–64)
  - `utils/.../envmanager/EnvOption.java` (58–65, `validateName`)
  - `utils/.../envmanager/EnvType.java` (50)
  - **Proposal:** small `Strings.requireNonBlank(value, field)` helper.
- Duplicate-key detection repeated in:
  - `utils/.../envmanager/EnvSchema.java` (13–18)
  - `utils/.../envmanager/EnvClassBuilder.java` (40–44)
  - **Proposal:** share one validation utility, or have `EnvClassBuilder` delegate
    to `EnvSchema`.

Lowest priority.
