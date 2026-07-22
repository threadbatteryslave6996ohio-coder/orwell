# Refactor backlog

Remaining extraction/cleanup work, ordered by value. (Earlier items — the apps/packages split,
the Spring migration of all servers, the `AppServer.spring()` descriptor, shared
logger/health/auth/JSON auto-configs, the invalid-JSON `@RestControllerAdvice`, the
`@RequireAuthentication` guard, the shared Testcontainers base, the removal of combined-server,
the orphaned launcher test, the klippy naming pass, and the `Logger`-service migration — are
done.)

## 0. Operational notes from the naming pass

Not backlog items, but worth knowing:

- **The Android module is not in the Maven reactor.** It builds with Gradle, so `mvn test` proves
  nothing about it. Changes there are unverified by CI here and want a real Gradle build.
- **The audit log is `klippy-server.txt`.** Nothing in-repo reads it but its test, so any external
  tooling shipping or rotating logs by an older name fails silently.
- **Logging goes through `dev.orwell.logging.Logger` everywhere** — see `packages/logger/README.md`
  for which sink to use and how it is wired. Raw `System.out`/`System.err` survives only in
  bootstrap and startup-failure paths that run before a logger can exist (`AppServer`,
  `AuthenticationStrategyConfiguration`, `undertow` `ServerRuntime`, `env-http` `EnvLoader`).
  Adding a new one anywhere else is a regression.
- **Two log-format contracts changed.** `CustomLogger`'s `.txt` lines now carry a leading ISO
  timestamp and render metadata as `key=value` (previously metadata was silently dropped).
  `apps/alerting`'s `ALERT_LOG_FILE` JSON now flattens fields to the top level instead of nesting
  them under `fields` — documented in that app's README. Jarvis's proxy audit trail was
  deliberately left on its original `event` schema, since it is a deployed security log.
- **`DatabaseLogger` was deleted.** It was dead code — no constructor call, no migration for its
  `app_logs` table. A network sink is fine, but only asynchronously: a synchronous send inside
  `Logger.log()` would put a round trip on every request path, and `FailSafeLogger` protects
  against a sink being *down*, not against it being *slow*. That is why `LokiLogger` batches from
  a bounded queue on its own thread and never blocks the caller.
- **One Postgres, one Redis.** `docker-compose.all-services.yml` is the only thing that creates
  them. Removed en route: `scripts/dev-stack.sh` (host-JVM loop with its own `orw-pg`/`orw-redis`),
  `scripts/docker-compose-stamped.sh` (dead code whose per-minute project names silently orphaned
  the data volume each run), and the DB-only compose files under `apps/klippy/server` and
  `apps/auth/http-based/server`. The per-app stacks and `local-stack.sh` now use the shared
  instance, and everything is on port 5432 — which also fixed the `secrets` database being
  referenced on 5435 by three files while nothing provisioned it.
- **App logs are pushed to Loki from inside the JVM.** `LokiLogger` (packages/logger) batches onto
  a bounded queue and ships from a daemon thread; `LOKI_URL`/`LOKI_TENANT_ID` are common keys on
  `AppServerEnv`. This replaced a Grafana Alloy collector that scraped per-service `.jsonl` files —
  that whole approach (the `alloy` service, its config, its nginx route, and the per-service log
  volumes) was removed, and with it the unbounded-log-file growth problem, since no file is
  written any more.
- **The send buffer is in memory, so crash-time logs are lost.** Entries queued but not yet shipped
  die with the process — including the ones written immediately before a crash, which are the ones
  worth having. This is the accepted cost of direct push over a file plus a collector. If it ever
  bites, the fix is a spill-to-disk buffer on overflow, not a synchronous send.
- **Nothing alerts when log shipping stops.** If `LOKI_URL` is unset or Loki is unreachable,
  `LokiLogger` drops and counts while every service stays healthy, and `log-analyzer` sees zero
  errors — identical to a clean stack. Drops are reported to stderr on an interval, which is
  better than silence but is not an alert. Wanted: a deadman check that the stream is still
  receiving.
- **`LOKI_URL` and `GRAFANA_LOKI_DATASOURCE_UID` are two independent pointers at the same Loki.**
  The servers write to one, `log-analyzer` reads from the other, and nothing verifies they agree.
  If they diverge, the analyzer silently reports all-clear forever.
- **Every service now holds the Loki endpoint.** Direct push means seven services carry
  `LOKI_URL` (and any credentials in it) instead of one collector. Rotating a Loki credential is
  a seven-service redeploy.
- **The Loki `app` label is not the compose service name for two services.** `clipboard-server`
  ships as `app="klippy-server"` and `jarvis-proxy` as `app="bucket-proxy"`, because the label is
  `orwell.app.name`. Documented in `apps/log-analyzer/README.md`.
- **jarvis-proxy's `audit.log` is still its own thing.** Separate `event` schema, written directly
  by `FileAuditLogger`, not pushed to Loki. If it should ever ship, it needs its own stream type,
  not a widening of the app pipeline.
- **DEPLOY ACTION — servers no longer write an app log file at all.** The Spring default sink is
  console + Loki push. Any external log rotation, volume mount, or shipping config keyed to
  `<app>.txt` or `<app>.jsonl` now watches a file nobody writes. `CustomLogger` still writes
  `.txt` where it is used directly (`EnvSnapshotLogger`, the auth startup hook).

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
- **Gmail webhook auth**: `GmailService.deliver()` still hand-attaches auth headers; the
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
