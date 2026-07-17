# Refactor backlog

Remaining extraction/cleanup work, ordered by value. (Earlier items — the apps/packages split,
the Spring migration of all servers, the `AppServer.spring()` descriptor, shared
logger/health/auth/JSON auto-configs, the invalid-JSON `@RestControllerAdvice`, the
`@RequireAuthentication` guard, the shared Testcontainers base, the removal of
combined-server, and the orphaned `ClippyServerLauncherTest` — are done.)

## 0. The clippy → klippy rename (done — one follow-up)

Everything is renamed: JVM and Kotlin identifiers, local dev Postgres, the audit log stream, the
client data files, the file-locker socket, the Android preferences, and every user-facing name.
The Azure Terraform was deleted rather than renamed; it is no longer used.

**Follow-up: drop the three legacy fallbacks once clients have rolled over.** They exist only to
carry pre-rename data forward, and each is listed in CLAUDE.md: `OfflineLogPath.LEGACY`,
`OfflineFileLockerClient.LEGACY_SOCKET_PATH`, and `KlippySettings.LEGACY_PREFERENCES_NAME`. Deleting
one early strands exactly the data it was added to save, so this is gated on rollout — no
pre-rename client, locker, or Android install still running — not on a release number.

Two things worth knowing if you touch this again:

- **The Android module is not in the Maven reactor.** It builds with Gradle, so `mvn test` proves
  nothing about it. Its rename is unverified by CI here and wants a real Gradle build.
- **External log tooling.** The audit file is now `klippy-server.txt`. Nothing in-repo reads it but
  its test, so anything shipping or rotating logs by the old name fails silently.

## 1. Common `springProperties` keys → `AppServerEnv` descriptor (completed)

Previously every `*Envs` class hand-mapped the same universal keys: `server.port` (10 copies),
`server.address` (6 copies — and auth lacks one, so the auth server always binds `0.0.0.0`),
`logging.file.name` (3), and the auth base-url (4 copies under **three different property
names**; these are now standardized on `orwell.auth.base-url`).

`AppServerEnv` now owns the common keys and each application adds only app-specific options and
property mappings.

```java
new AppServer(AnalyzerApplication.class, "analyzer", AnalyzerEnvs.ENV)
```

Sub-items:
- Common environment variables are `SERVER_ADDRESS`, `SERVER_PORT`, `LOGGING_FILE_NAME`, and
  `AUTH_BASE_URL`; the shared bootstrap maps them to Spring properties.

## 2. `spring-boot-maven-plugin` block → `server-parent` pluginManagement

12 poms repeat the identical plugin block (repackage + `exec` classifier), differing only in
`mainClass`. The plugin natively resolves the `start-class` property, so the parent can own the
block and each app pom shrinks to `<properties><start-class>…</start-class></properties>`.

Related: `apps/auth/pom.xml` and `apps/secrets-manager/pom.xml` have **no parent** and
re-declare the Spring BOM + compiler/surefire pluginManagement. Point them at the root (or
`server-parent`) and delete the duplicated management sections.

## 3. Smaller cleanups

- **`sha256Hex` triplication**: byte-for-byte copies in `DetectionService` and
  `AnalysisWorker` (producer/consumer of the same frame-hash contract — divergence breaks every
  frame with "frame hash mismatch"), plus a third digest impl in `LogAnalyzerService.fingerprint`.
  Hoist one helper into `packages/primitives`.
- **Detection alert client**: `DetectionService.detect()` hand-builds its alert POST (no
  timeout, no blank-URL guard, no transient/terminal outcome split) while `log-analyzer`'s
  `AlertClient` already encapsulates all of that for the same `/alerts` endpoint. Promote
  `AlertClient` to a shared module and use it from both. Add connect/request timeouts either way.
- **Dead `from(Map)` wrappers**: `AnalyzerEnvs`/`GmailEnvs`/`AlertEnvs`/`LogAnalyzerEnvs` each
  ship a zero-caller `from(Map)` alias; delete them (callers can use `X.ENV.from(map)`).
- **Orphaned test**: `ClippyServerLauncherTest` is named after a deleted class and only tests
  `EnvFiles.load` (already covered in `packages/env`); delete or move the assertion.
- **`GmailService.gmail()` 401-retry** rebuilds the identical `HttpRequest` twice; extract a
  `request(url, body, token)` helper so the primary and retry paths cannot drift.
- **Gmail webhook auth**: `GmailService.save()` still hand-attaches auth headers; the
  authenticated-client pattern (`ClientAuthSession`-style token cache with 401 refresh) exists in
  klippy's client-core and is also re-implemented in `SecretsManagerClient`/`BucketProxyClient`.
  One shared authenticated-HTTP helper would replace four variants.
- **`KeeboarderWebSocketRuntime`** reduces to a
  `@Bean(destroyMethod = "close") @ConditionalOnBooleanProperty RedisClientCache` whose bean
  method calls `ChatEndpoint.initialize(...)` — the holder class and its mutable state disappear.
- **Logger fallback name**: `LoggerConfiguration` defaults to `"app"` when `orwell.app.name` is
  missing (a context booted outside the descriptor logs to the wrong stream silently). Consider
  failing fast instead, or deriving from `spring.application.name`.
- **Route prefixes**: with combined-server gone, only keeboarder's `/api` prefix is real. The
  remaining `${x.server.route-prefix:}` placeholders (klippy, auth, secrets, proxy) should either
  be deleted like the migrated apps' were, or generalized to `server.servlet.context-path` at the
  descriptor level so `/health` moves together with app routes.
- **`SharedHealthController` allocations**: providers build intermediate maps copied into the
  response on every poll; a `contribute(Map target)` signature would avoid the churn.
- **dev-stack startup**: the five JVMs start sequentially (sum of boot times); launch all five
  first, then poll all health URLs (max of boot times).
- **Adopt `@RequireAuthentication` in the older apps**: klippy/keeboarder/proxy still hand-roll
  their 401 responses (different bodies: Spring default error JSON, empty body,
  `{"status":"unauthorized"}`). Adopting the shared guard means aligning those response
  contracts first; klippy/proxy also do clientId-match checks that stay in the controller.

## 4. Live duplication (folded in from the retired removing-redundant-code.md)

- **`CooldownTracker` byte-for-byte duplicate**: identical classes (only the package line
  differs) in `apps/alerting/.../CooldownTracker.java` and
  `apps/jarvis/detection/.../CooldownTracker.java`; `apps/log-analyzer`'s
  `AlertCooldownTracker` is a third, evolved variant of the same concept. Extract one shared
  implementation (log-analyzer's reservation semantics are the best starting point).
- **Secrets-manager DTO triplication**: the same response shapes exist as admin records,
  accessor records, and client records
  (`apps/secrets-manager/server/.../admin/*Response.java`, `accessor/*Response.java`,
  `apps/secrets-manager/client/.../dto/*.java`); the create/update request records also pair up
  near-identically. Merge into shared records.
- **Maven shade plugin declared unconfigured in 4 klippy client poms**
  (linux/mac/dummy/offline-sync); declare once in a parent `pluginManagement`.
- **Two logging facades**: alerting's app-local `JsonLogger` (JSON lines via the shared
  `JsonLineFileWriter`) coexists with the shared `dev.orwell.logging.Logger`/`CustomLogger`
  (plain text). Pick one contract; today "add a log line" has two conflicting answers.
- **Dead code**: `packages/primitives/.../Flag.java` has zero consumers — delete it.
- **Unguarded auto-config registry**: nothing tests that the four entries in server-bootstrap's
  `META-INF/spring/...AutoConfiguration.imports` resolve; a typo silently drops the shared
  `/health` endpoint, 401 guard, logger, and auth-strategy beans. Add a `@SpringBootTest`
  asserting those beans exist.
