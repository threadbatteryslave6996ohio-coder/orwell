# Refactor backlog

Remaining extraction/cleanup work, ordered by value. (Earlier items — the apps/packages split,
the Spring migration of all servers, the `AppServer.spring()` descriptor, shared
logger/health/auth/JSON auto-configs, the invalid-JSON `@RestControllerAdvice`, the
`@RequireAuthentication` guard, the shared Testcontainers base, the removal of
combined-server, and the orphaned `ClippyServerLauncherTest` — are done.)

## 0. The clippy → klippy rename: what's left

The JVM identifiers, local dev Postgres, Terraform labels and everything user-facing are renamed.
Three groups still say `clippy` **on purpose** — each is a contract, not a label:

- **Audit log stream** `clippy-server` → `clippy-server.txt` (`ClipboardEntryController`, and the
  app name in `KlippyServerApplication`). Cheap to rename, but nothing in-repo reads the file
  except one test, so it fails silently in whatever ships the logs. Needs someone to confirm no
  external tooling keys on the name.
- **Deployed Azure infra** — `name_prefix`, `postgres_database_name`, `storage_container_name`,
  `postgres_admin_username`, the cloud-init paths. `administrator_login` and `custom_data` are
  both ForceNew: renaming replaces the Postgres server and the VM. Only worth doing as a
  blue/green migration, and only if that infrastructure is moving anyway.
- **Client contracts** — `clippy-offline-clipboard.json` and `/tmp/clippy-offline-file-locker.sock`.
  The socket is the sharper one: a renamed client and an old locker bind different paths, never
  meet, and the lock stops excluding — two processes then write the same file. Needs a
  read-both-write-new release plus a deprecation window before the fallback drops.

**The Android client is untouched and is its own project.** It builds with Gradle, outside the
Maven reactor, so the JVM rename never reached it: `ClippyApp`/`ClippyTheme`/`ClippySettings`/
`ClippyApi` in `MainActivity.kt`, `Theme.Clippy`, `rootProject.name = "ClippyAndroid"`, and the
user-visible `app_name` string still say Clippy. Renaming the Kotlin identifiers is compile-checked
like the Java was — but `getSharedPreferences("clippy", …)` is a **data contract**: change that key
and every installed app forgets its server URL and client token. Rename the identifiers and
`app_name` freely; migrate the preferences key only by reading the old one and writing the new.

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
